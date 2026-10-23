# The tools this repository's builds, hooks, gates and scripts call, for `brew bundle`.
# scripts/setup-mac.sh runs it; EJ_OPERATOR=1 adds what reaching the clusters takes.

# Homebrew strips PATH before reading this file and keeps the caller's in HOMEBREW_PATH.
def on_path?(command)
  ENV.fetch("HOMEBREW_PATH", ENV["PATH"]).split(":").any? { |dir| File.executable?(File.join(dir, command)) }
end

tap "fluxcd/tap"

# Build, run and the local stack
brew "git"
brew "gh"
brew "jq"
brew "yq"
brew "direnv"
brew "nvm"
brew "postgresql@17"
brew "uv"
# A cask refuses to overwrite an app installed without Homebrew, so skip what is already there.
cask "docker-desktop" unless File.exist?("/Applications/Docker.app")
cask "ijhttp" unless on_path?("ijhttp")
cask "claude-code" unless on_path?("claude")

# The commit hooks and the /verify gates
brew "pre-commit"
brew "gitleaks"
brew "shellcheck"
brew "shfmt"
brew "ruff"
brew "hadolint"
brew "opentofu"
brew "helm"
brew "fluxcd/tap/flux"
brew "lychee"

# Optional jobs: k3d rehearsal, load tests, diagrams, stickers
brew "k3d"
brew "crane"
brew "k6"
brew "graphviz"
brew "librsvg"

if ENV["EJ_OPERATOR"] == "1"
  brew "wireguard-tools"
  brew "kubernetes-cli"
  brew "kubectx"
  brew "k9s"
  brew "sops"
  brew "age"
  cask "keepassxc" unless File.exist?("/Applications/KeePassXC.app")
end
