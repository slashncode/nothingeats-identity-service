# Inner loop for this service in the local k3d cluster. Start it from the hub repo with
# `just dev <service>`: Flux steps aside, Tilt rebuilds with Jib on every change and redeploys
# with the same chart and values that Flux uses (read from ../nothingeats-gitops).

allow_k8s_contexts('k3d-nothingeats')

repo = os.path.basename(config.main_dir)  # nothingeats-<context>-service
release = repo.replace('nothingeats-', '', 1)  # <context>-service
image = 'ghcr.io/slashncode/' + repo
workspace = os.path.dirname(config.main_dir)
gitops_release = os.path.join(workspace, 'nothingeats-gitops', 'apps', 'base', release, 'release.yaml')
local_chart = os.path.join(workspace, 'nothingeats-charts', 'charts', 'backend-service')
chart = local_chart if os.path.exists(local_chart) else 'oci://ghcr.io/slashncode/charts/backend-service'
arch = 'arm64' if str(local('uname -m', quiet=True)).strip() in ['arm64', 'aarch64'] else 'amd64'

# Jib pushes straight into the k3d registry; no Docker build involved.
custom_build(
    image,
    './gradlew :app:jib --console=plain -Djib.to.image=$EXPECTED_REF -Djib.allowInsecureRegistries=true -Djib.from.platforms=linux/' + arch,
    deps=['domain/src', 'application/src', 'adapters/src', 'app/src', os.path.join(workspace, 'nothingeats-kotlin-platform')],
    ignore=['**/build', '**/.gradle', '**/.kotlin'],
    skips_local_docker=True,
)

# Same chart and values as the Flux HelmRelease; Tilt swaps in the freshly built image.
k8s_yaml(local(
    ['bash', '-c', 'yq ".spec.values" "$0" | helm template "$1" "$2" -f -', gitops_release, release, chart],
    quiet=True,
))

k8s_resource(release + '-migrate', labels=['service'])
k8s_resource(release, resource_deps=[release + '-migrate'], port_forwards=['18080:8080'], labels=['service'])
