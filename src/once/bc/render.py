"""Template engine for copying resource templates with placeholder substitution."""
from __future__ import annotations

import os
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable

from .core import Opts, StepFn, WfStep, ok, to_workflow


@dataclass(frozen=True, init=False)
class Delimiters:
    tag_open: str = "{"
    tag_close: str = "}"
    filter_open: str = "{"
    filter_close: str = "}"

    def __init__(
        self,
        tag_open: str = "{",
        tag_close: str = "}",
        filter_open: str = "{",
        filter_close: str = "}",
        **kwargs: str,
    ) -> None:
        # Accept TypeScript-style constructor keys too.
        tag_open = kwargs.pop("tagOpen", tag_open)
        tag_close = kwargs.pop("tagClose", tag_close)
        filter_open = kwargs.pop("filterOpen", filter_open)
        filter_close = kwargs.pop("filterClose", filter_close)
        if kwargs:
            unknown = ", ".join(kwargs)
            raise TypeError(f"unknown delimiter keys: {unknown}")
        object.__setattr__(self, "tag_open", tag_open)
        object.__setattr__(self, "tag_close", tag_close)
        object.__setattr__(self, "filter_open", filter_open)
        object.__setattr__(self, "filter_close", filter_close)

    @property
    def tagOpen(self) -> str:  # noqa: N802 - compatibility alias
        return self.tag_open

    @property
    def tagClose(self) -> str:  # noqa: N802 - compatibility alias
        return self.tag_close

    @property
    def filterOpen(self) -> str:  # noqa: N802 - compatibility alias
        return self.filter_open

    @property
    def filterClose(self) -> str:  # noqa: N802 - compatibility alias
        return self.filter_close


DEFAULT_DELIMITERS = Delimiters()


def _is_delimiters(x: Any) -> bool:
    return isinstance(x, Delimiters) or (
        isinstance(x, dict)
        and {"tagOpen", "tagClose", "filterOpen", "filterClose"}.issubset(x.keys())
    )


def _as_delimiters(x: Any | None) -> Delimiters:
    if x is None:
        return DEFAULT_DELIMITERS
    if isinstance(x, Delimiters):
        return x
    return Delimiters(
        tag_open=x["tagOpen"],
        tag_close=x["tagClose"],
        filter_open=x["filterOpen"],
        filter_close=x["filterClose"],
    )


def _stringify(v: Any) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def selmer(content: str, data: dict[str, Any], delimiters: Delimiters | dict[str, str] | None = None) -> str:
    """Render simple ``{{ var }}``-style placeholders against ``data``."""
    d = _as_delimiters(delimiters)
    open_pat = re.escape(d.tag_open + d.filter_open)
    close_pat = re.escape(d.filter_close + d.tag_close)
    pattern = re.compile(rf"{open_pat}\s*([A-Za-z0-9_.-]+)\s*{close_pat}")
    return pattern.sub(lambda m: _stringify(data.get(m.group(1))), content)


NON_REPLACED_EXTS = {"jpg", "jpeg", "png", "gif", "bmp", "bin"}


def _extension_of(path: Path) -> str:
    suffix = path.suffix[1:].lower()
    return suffix


def _walk_files(directory: Path) -> Iterable[Path]:
    for root, _dirs, files in os.walk(directory):
        for file in files:
            p = Path(root) / file
            if p.is_file():
                yield p


def _copy_dir(src_dir: Path, target_dir: Path, data: dict[str, Any] | None, delimiters: Any | None) -> None:
    if not src_dir.exists():
        raise FileNotFoundError(f"template directory not found: {src_dir}")
    for file in _walk_files(src_dir):
        target_file = target_dir / file.relative_to(src_dir)
        target_file.parent.mkdir(parents=True, exist_ok=True)
        if data is not None and _extension_of(file) not in NON_REPLACED_EXTS:
            content = file.read_text(encoding="utf-8")
            target_file.write_text(selmer(content, data, delimiters), encoding="utf-8")
        else:
            shutil.copyfile(file, target_file)
        try:
            os.chmod(target_file, file.stat().st_mode)
        except OSError:
            pass


TransformSrc = str | Callable[[str, dict[str, Any]], str]


def _copy_template_dir(
    *,
    template_dir: Path,
    target_dir: Path,
    data: dict[str, Any],
    src: TransformSrc,
    target: str | None,
    files: dict[str, str] | None,
    delimiters: Any | None,
    raw: bool,
) -> None:
    if callable(src):
        if files is None:
            raise ValueError("files is required when src is a function")
        base = Path(str(target_dir) + (selmer(target, data) if target else ""))
        for key, to in files.items():
            content = src(key, data)
            if not raw:
                content = selmer(content, data, delimiters)
            target_file = base / selmer(to, data)
            target_file.parent.mkdir(parents=True, exist_ok=True)
            target_file.write_text(content, encoding="utf-8")
        return

    src_dir = template_dir / selmer(src, data)
    base = target_dir / selmer(target, data) if target else target_dir
    _copy_dir(src_dir, base, None if raw else data, delimiters)


def _candidate_resource_roots() -> list[Path]:
    roots: list[Path] = []

    # Resources live only under the project-level src/resources directory.
    starts = [Path.cwd(), Path(__file__).resolve()]
    for start in starts:
        cur = start if start.is_dir() else start.parent
        for parent in [cur, *cur.parents]:
            roots.append(parent / "src" / "resources")

    # Deduplicate while preserving order.
    seen: set[Path] = set()
    out: list[Path] = []
    for root in roots:
        try:
            r = root.resolve()
        except OSError:
            r = root
        if r not in seen:
            seen.add(r)
            out.append(r)
    return out


def resource_roots() -> list[Path]:
    """Return candidate roots that may contain resource templates."""
    return [root for root in _candidate_resource_roots() if root.exists()]


def find_template_root(template: str) -> Path:
    for root in resource_roots():
        if (root / template).exists():
            return root
    roots = ", ".join(str(r) for r in _candidate_resource_roots())
    raise FileNotFoundError(f"could not locate template {template!r}; searched: {roots}")


TEMPLATE_KEYS = {
    "template",
    "targetDir",
    "overwrite",
    "dataFn",
    "templateFn",
    "postProcessFn",
    "transform",
}


def render(opts: Opts) -> Opts:
    """Render every template in ``opts['templates']``."""
    templates = opts.get("templates")
    if templates is None:
        raise ValueError("templates should never be nil")

    for edn in templates:
        data_fn = edn.get("dataFn", lambda d, _opts=None: d)
        data = {k: v for k, v in edn.items() if k not in TEMPLATE_KEYS}
        data["module"] = opts.get("module")
        data["profile"] = opts.get("profile")
        final_data = data_fn(data, opts)

        template_root = find_template_root(edn["template"])
        template_dir = template_root / edn["template"]
        target_dir = Path(edn["targetDir"])
        if target_dir.exists():
            overwrite = edn.get("overwrite")
            if overwrite:
                if overwrite == "delete":
                    shutil.rmtree(target_dir, ignore_errors=True)
            else:
                raise FileExistsError(f"{target_dir} already exists (and overwrite was not true).")

        for transform in edn.get("transform", []):
            src = transform[0]
            target: str | None = None
            files: dict[str, str] | None = None
            delimiters: Any | None = None
            raw = False
            for item in transform[1:]:
                if item == "raw":
                    raw = True
                elif item == "only":
                    continue
                elif isinstance(item, str):
                    target = item
                elif _is_delimiters(item):
                    delimiters = item
                elif isinstance(item, dict):
                    files = item
            _copy_template_dir(
                template_dir=template_dir,
                target_dir=target_dir,
                data=final_data,
                src=src,
                target=target,
                files=files,
                delimiters=delimiters,
                raw=raw,
            )
    return ok(opts)


def _wire(step: str, _step_fns: list[StepFn]) -> tuple[WfStep, str | None]:
    if step == "big-config.render/start":
        return render, "big-config.render/end"
    return (lambda o: o), None


render_templates = to_workflow(first_step="big-config.render/start", wire_fn=_wire)


renderTemplates = render_templates
