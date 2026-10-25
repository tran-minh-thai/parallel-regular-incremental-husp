# results-probe

Feasibility probes from the development machine (Apple M5, JDK 26). They decide whether a mechanism
is worth building. They are not experiments: single runs, no warm-up, other work sometimes running on
the same machine. No timing in this directory may be quoted as a measurement.

The exactness columns (missing, extra, utility and period mismatches against an oracle on the same
prefix) do not depend on the machine and stay valid. Each run directory carries `run_meta.json` with
the command, commit, tree state and source and data hashes; `logs/` holds the console output of every
run, including aborted and failed ones, and `*_batch_meta.txt` records the script that launched a
batch. Absolute paths in those batch files were replaced by placeholders before commit.
