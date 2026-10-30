# Discovery windows: largest sequence utility and an output-size certificate

Machine-independent quantities (data properties and counts), computed from the datasets and from the BUD3 rows of
the E2 cells on branch `campaign-9950x` at commit `a3cfd47372b7eaab9afe498229999bfdcbc3c9a5` (10 cells recorded,
9 with BUD3 rows: VS-CHAIN96-CW, VS-CHAIN101-CW, VS-CHAIN152-CW, schedules S1, S2, S3). Dataset files were checked
against the SHA-256 in each cell's `run_meta.json`.

```bash
python3 rho_offline.py <repository> rho_per_dataset.tsv > rho_offline_output.txt
python3 certificate.py <repository> <this directory> > certificate_output.txt
```

## rho = largest sequence utility in the window / discovery threshold

The discovery threshold is recomputed from its definition, tau = ceil(delta * U(D_t)) - ceil(delta * U(D_0)) + 1, with
the batches cut as `ExpUtil.split(fineRatios(k, dOld))` and the window holding every sequence after the old part (no
re-anchor can happen before the last query of S1, S2 or S3). Against the 54 discovery rows of the artifacts, which
record the threshold they used, the recomputed tau differs by 0 in every row, and the sequence counts match in every
row. `rho_per_dataset.tsv` gives, for every dataset and schedule of `experiments/campaign_9950x.json`, the largest rho
over the queries that may attempt a discovery and how many of them reach rho >= 1, 2, 4, 8.

Over the 66 attempts (54 finished, 12 aborted): every attempt with rho < 1 finished (46 of 46, none cost more than
F); of the 20 with rho >= 1, 12 were aborted and 8 finished cheaply (rho up to 2.89, VS-CHAIN96-CW S1). The four
aborts caused by the time budget (VS-CHAIN152-CW) have rho 7.7 to 15.6.

## Output-size certificate (`certificate.py`)

In a suffix window the engine keeps a pattern only if its first occurrence is within B sequences of the window start,
consecutive occurrences are within B of each other, and (floor prune) the last one is within B of the window end.
For two sequences s1, s2 of the window meeting these conditions, a day-basket A of s1 and C of s2, Z = A n C, and m
the fewest items of Z (largest utility in s1 plus s2 first) whose utilities reach tau, every itemset X with
top-m(Z) <= X <= Z qualifies, so the exact discovery answer holds at least 2^(|Z| - m) patterns. Further occurrences
only shorten the gaps and add utility, so checking s1 and s2 suffices. The search tries only sequences with utility
at least tau / 8 as members of a pair; pairs not tried only weaken the bound.

Falsification check: for every finished discovery, 2^c must not exceed the number of candidates the engine returned.
Result: 0 of 54 above, but only 1 of the 54 has c > 0 (VS-CHAIN96-CW S1 query 1: c = 4, 2591 candidates), so the check
is nearly vacuous. The certificate is 0 at the four time-budget aborts of VS-CHAIN152-CW: the store-type sequence lies
more than B sequences after the window start there, and the growth comes from the union of many itemsets, each
regular through different customers and above the threshold through the store sequence's utility, which a bound
built from one pair cannot see.

An earlier version without the regularity conditions (every subsequence of one sequence that holds the top-m items)
was refuted by the same check: it gave c = 70 at VS-CHAIN96-CW S1 query 1, which returned 2591 candidates.
