#!/bin/sh
#
# Runs one boot script from `runcmd`, keeps its output in /var/log/event-junkie/<name>.log, and
# records a failure instead of stopping the boot (#4141). cloud-init runs `runcmd` as one `sh`
# script without `-e`, so only the last line's exit counted: a `harden.sh` that stopped halfway
# still booted as SUCCESS. The last `runcmd` line now fails the boot when any step failed, and the
# steps after a failed one still run, so a harden defect does not also cost the tunnel.

set -u

readonly name="$1"
readonly log_dir=/var/log/event-junkie
readonly failed=/run/event-junkie-boot-failed

if [ "${name}" = --result ]; then
    if [ -s "${failed}" ]; then
        echo "boot: failed: $(tr '\n' ' ' <"${failed}")" >&2
        exit 1
    fi
    echo "boot: every step succeeded"
    exit 0
fi

install -d -m 0750 "${log_dir}"
rc_file="$(mktemp)"
{
    rc=0
    "/opt/event-junkie/${name}.sh" 2>&1 || rc=$?
    echo "${rc}" >"${rc_file}"
} | tee "${log_dir}/${name}.log"
rc="$(cat "${rc_file}")"
rm -f "${rc_file}"

if [ "${rc}" -ne 0 ]; then
    echo "boot: ${name}.sh failed with exit ${rc}, see ${log_dir}/${name}.log" >&2
    echo "${name}" >>"${failed}"
fi
exit 0
