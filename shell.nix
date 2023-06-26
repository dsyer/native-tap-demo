with import <nixpkgs> { };
pkgs.mkShell{

  name = "env";
  buildInputs = [
    azure-cli
    (google-cloud-sdk.withExtraComponents [google-cloud-sdk.components.gke-gcloud-auth-plugin])
  ];

}