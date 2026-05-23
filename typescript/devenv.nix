{ pkgs, lib, config, inputs, ... }:

{
  languages.javascript = {
    enable = true;
    npm.enable = true;
  };
  languages.ansible.enable = true;
  languages.typescript.enable = true;
  languages.opentofu.enable = true;
  packages = [
    pkgs.nodejs_22
    pkgs.babashka
    pkgs.awscli2
    pkgs.skopeo
    pkgs.hcloud
    pkgs.doctl
  ];
}
