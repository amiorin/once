{ pkgs, lib, config, inputs, ... }:

{
  languages.clojure.enable = true;
  languages.ansible.enable = true;
  languages.opentofu.enable = true;
  languages.python = {
    enable = true;
    uv.enable = true;
  };
  packages = [
    pkgs.babashka
    pkgs.jet
    pkgs.hcl2json
    pkgs.awscli2
    pkgs.skopeo
    pkgs.hcloud
    pkgs.doctl
  ];
}
