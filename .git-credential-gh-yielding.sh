#!/bin/bash
# Per-repo credential helper: uses GH_REPO_USER for github.com auth.
# Reads git credential protocol from stdin, outputs credentials to stdout.
# Set GH_REPO_USER in .env to the GitHub account that owns this repo.

req_host="" req_protocol=""

while IFS='=' read -r key value; do
    [ -z "$key" ] && break
    case "$key" in
        host)     req_host="$value" ;;
        protocol) req_protocol="$value" ;;
    esac
done

if [ "$req_host" != "github.com" ]; then
    exec gh auth git-credential "$@"
fi

if [ -z "$GH_REPO_USER" ]; then
    echo "git-credential-gh: GH_REPO_USER not set — set it in .env to the repo owner" >&2
    exec gh auth git-credential "$@"
fi

# Save active user, switch to GH_REPO_USER, get token, restore
_prev_user=$(gh auth status --json hosts --jq '.hosts."github.com"[] | select(.active) | .login' 2>/dev/null)
gh auth switch --user "$GH_REPO_USER" --hostname github.com 2>/dev/null
TOKEN=$(gh auth token --hostname github.com 2>/dev/null)
if [ -n "$_prev_user" ]; then
    gh auth switch --user "$_prev_user" --hostname github.com 2>/dev/null
fi

printf 'protocol=%s\nhost=%s\nusername=%s\npassword=%s\n' \
    "$req_protocol" "$req_host" "$GH_REPO_USER" "$TOKEN"
