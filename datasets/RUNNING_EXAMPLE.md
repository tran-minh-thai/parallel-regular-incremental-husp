# Consistent example dataset for the paper (Running Example)

An original dataset, **designed specifically for this study** (not reused from other papers),
serving two purposes at once: (1) illustrating the contributions throughout the paper;
(2) testing the correctness of the code. It has been **validated against the original RHusp**
(`codeThamKhao/AlgoRHUSPMiner.java`): the in-memory oracle's result set matches exactly.

- Data files: [`example_seq.txt`](example_seq.txt), [`example_eui.txt`](example_eui.txt)
  *(this is the standard example dataset used to test the program and as the source for the paper)*
- Standard parameters: **δ = 0.10** (minUtil = δ·UD), **ρ = 0.6** (maxReg = ρ·N **over the ENTIRE
  CURRENT DB**, recomputed per batch), batch split **6/3**.

> **P-RIncHUSP mechanism (current, EXACT version):** the result equals a full re-mine (recall 1.0),
> not a high-coverage approximation. With the seed-split factor λ=1 the seed threshold is θ₀ = δ·U(D_old),
> so seeding keeps only the HS patterns of D_old; each batch re-matches those by the correct
> **MAX-measure** and promotes any that reach the growing minUtil. Patterns too weak on D_old to be
> seeded — the whole `{d,e}` family here — are recovered at query time by the **discovery phase**, which
> mines the accumulated ΔD at minUtil−θ₀ = δ·U(ΔD). Two seed bounds close two independent gaps:
> regularity pruning at ρ·N_final, and this discovery phase. (Historical note: an earlier version used a
> μ<1 semi-HS buffer and was a high-coverage approximation missing `<(e)>`; the discovery phase replaced
> it and makes the result exact.)

## 1. Items and external utilities (relabeled when used in the paper)

| id (code) | paper symbol | external utility `pr` | role |
|:--:|:--:|:--:|---|
| 1 | a | 2 | anchor item, appears in every sequence |
| 2 | b | 2 | STABLE companion of the pattern `<(a)(b)>` |
| 3 | c | 4 | **high-quantity, NON-REGULAR** item (illustrates regularity-based pruning) |
| 4 | d | 5 | **EMERGING** item (main contribution) |
| 5 | e | 4 | **EMERGING** item, forms the itemset `<(d e)>` |

## 2. Database (9 sequences) — format `item[quantity]`

| SID | D_old / ΔD | sequence (paper symbols) | design note |
|:--:|:--:|---|---|
| 0 | D_old | `(a:1)(b:12)` | stable pattern a->b |
| 1 | D_old | `(a:1)(d:1 e:1)` | d,e small quantity (emerging but weak) |
| 2 | D_old | `(a:1 c:40)(b:12)` | c high quantity but only in 1 sequence -> non-regular |
| 3 | D_old | `(a:1)(b:12)` | |
| 4 | D_old | `(a:1)(d:1 e:1)` | d,e small |
| 5 | D_old | `(a:1)(b:12)` | |
| 6 | ΔD | `(a:1)(d:10 e:10)` | d,e SURGE in the new batch |
| 7 | ΔD | `(a:1)(b:12)(d:10 e:10)` | |
| 8 | ΔD | `(a:1)(d:2)(d:10 e:10)` | d appears **twice** -> tests the MAX-measure |

## 3. Incremental DEUCS update (illustrates Lemma 1 — accumulation)

`UD` and every DEUCS/SWU cell of `D_new` = the `D_old` value **plus** the contribution from `ΔD` only,
without rescanning history:

| quantity | D_old | ΔD | D_new = D_old + ΔD |
|---|--:|--:|--:|
| UD | 286 | 310 | **596** |
| SWU[a] | 286 | 310 | 596 |
| SWU[b] | 264 | 116 | 380 |
| SWU[d] | 22 | 310 | 332 |
| SWU[c] | 186 | 0 | 186 *(c not in ΔD)* |
| DEUCS_S[a→b] | 264 | 116 | 380 |
| DEUCS_S[a→d] | 22 | 310 | 332 |
| DEUCS_I[d,e] | 22 | 310 | 332 |

## 4. Mining results (exact RHusp oracle)

**D_old (6 sequences):** minUtil = 29, θ₀(λ=1) = δ·U(D_old) = 29, seedMaxReg = ρ·N_final = 5
- Seeded (HS of D_old, utility ≥ θ₀ = 29): `<(a)(b)>`=104, `<(b)>`=96  *(stable patterns)*
- NOT seeded (utility < θ₀ = 29): the whole `{d,e}` family — `<(a)(d e)>`=22, `<(d e)>`=18,
  `<(a)(d)>`=14, `<(a)(e)>`=12, `<(a)>`=12. At λ=1 there is no semi-HS buffer; these are simply
  below the seed threshold and are recovered later by discovery, not by maintenance.

**D_new (9 sequences):** minUtil = 60, maxReg = 5
- Correct high-utility set (8 patterns, from the RHusp oracle):

| pattern (paper) | pattern (code) | utility | maxPer | group | P-RIncHUSP |
|---|---|--:|--:|---|:--:|
| `<(a)(b)>` | `<(1)(2)>` | 130 | 2 | stable | yes |
| `<(b)>` | `<(2)>` | 120 | 2 | stable | yes |
| `<(d e)>` | `<(4 5)>` | 288 | 3 | emerging (itemset, DEUCS_I) | yes |
| `<(d)>` | `<(4)>` | **160** | 3 | emerging (max-measure: 160 != 170) | yes |
| `<(a)(d e)>` | `<(1)(4 5)>` | 298 | 3 | emerging | yes |
| `<(a)(d)>` | `<(1)(4)>` | 170 | 3 | emerging | yes |
| `<(a)(e)>` | `<(1)(5)>` | 138 | 3 | emerging | yes |
| `<(e)>` | `<(5)>` | 128 | 3 | emerging (recovered by discovery) | yes |

-> P-RIncHUSP recovers **8/8 = 100%** (exact): the seeded `<(a)(b)>`, `<(b)>` plus the six `{d,e}`-family patterns (including `<(e)>`) recovered by discovery.

## 5. Where each contribution is illustrated

1. **Accumulative incremental update (Lemma 1):** §3 table — ΔD is appended at the tail, every cell accumulates.
2. **Regularity-based pruning (Corollary 2):** `<(c)>` has utility = 40·4 = **160 ≥ minUtil**
   but appears only in SID 2 -> maxPer = max(3, 9−2) = 7 > maxReg = 5 -> **rejected**. A high-utility
   pattern is rejected *purely due to non-regularity*.
3. **MAX utility measure (bug fix):** sequence 8 contains d twice (d:2 and d:10). `<(d)>`
   takes the maximum match = 50 (not 50+10) -> total utility **160**, not 170 as with naive
   accumulation. This is also where P-RIncHUSP BEATS RIncHusp: with the correct measure, `<(d)>` is
   promoted; RIncHusp updates naively / matches greedily and therefore misses this pattern.
4. **Discovery recovers the emerging family (exactness):** at λ=1 the `{d,e}` family (utility < θ₀=29 on
   D_old) is NOT seeded. After ΔD it surges; the discovery phase mines the accumulated ΔD at
   minUtil−θ₀ = δ·U(ΔD) = 31 and recovers the whole family, giving recall 1.0. This replaces the old
   μ<1 buffer, which only approximated.
5. **Why `<(e)>` needs discovery, not maintenance:** at seeding, `<(e)>` is below θ₀ (item e sits at the
   end of an event -> remaining utility 0, upper bound too low), so no seed node exists and maintenance
   alone can never promote it. The discovery phase, mining ΔD directly, is exactly what recovers it —
   the concrete reason both bounds are needed for exactness.
6. **Directed DEUCS (I and S):** `<(d e)>` co-occurs within the same event (DEUCS_I);
   `<(a)(b)>`, `<(a)(d)>` follow the sequence order (DEUCS_S).

## 6. Correctness test + algorithm contrast (δ=0.10, ρ=0.6, batch 6/3)

| Algorithm | mechanism | HS | coverage | note |
|---|---|--:|--:|---|
| **RHusp** (oracle) | exact re-mine | 8 | 100% | reference patterns (== AlgoRHUSPMiner) |
| **P-RIncHUSP** (proposed) | seed + maintain + discover, parallel | 8 | **100%** | exact; correct max-measure |
| Proposed-sequential (T=1) | as above, 1 thread | 8 | 100% | same result, speedup reference point |
| **RIncHusp** [Ishita2022] | incremental, naive update | 6 | **75%** | misses `<(d)>` due to greedy match |

The two differ in mechanism. RIncHusp keeps the `{d,e}` family in its μ<1 semi-HS buffer and promotes
on surge, but updates by a greedy match, gets `<(d)>` wrong (170 not 160), and has no way to reach
patterns that were below its buffer floor. P-RIncHUSP does not buffer at all (λ=1): it seeds only the
D_old HS, updates by the correct **max-measure** (so `<(d)>`=160 is right), and recovers the entire
`{d,e}` family — including `<(e)>` — through the discovery phase, which RIncHusp has no equivalent of.
That is why P-RIncHUSP is exact (8/8) and RIncHusp is not (6/8). *No pattern is a false positive.*

## 7. Reproduction

```bash
javac --release 11 -d out $(find src/main/java -name '*.java')
# exact config + oracle, prints recall (RunIncremental uses the adaptive default, not the exact miner):
java -cp out test.RecallProbe datasets/example_seq.txt datasets/example_eui.txt 0.10 0.60 1
# -> oracle HS=8 | P-RIncHUSP HS=8 recall=100.00%
```
