with import <nixpkgs> { };
pkgs.mkShell{

  name = "env";
  buildInputs = [
    azure-cli
  ];

}