# Consistent example dataset for the paper (Running Example)

An original dataset, **designed specifically for this study** (not reused from other papers),
serving two purposes at once: (1) illustrating the contributions throughout the paper;
(2) testing the correctness of the code. It has been **validated against the original RHusp**
(`codeThamKhao/AlgoRHUSPMiner.java`): the in-memory oracle's result set matches exactly.

- Data files: [`example_seq.txt`](example_seq.txt), [`example_eui.txt`](example_eui.txt)
  *(this is the standard example dataset used to test the program and as the source for the paper)*
- Standard parameters: **δ = 0.10** (minUtil = δ·UD), **ρ = 0.6** (maxReg = ρ·N **over the ENTIRE
  CURRENT DB**, recomputed per batch), batch split **6/3**.

> **P-RIncHUSP mechanism (current version):** maintains the HS + SHS tree *persistently across batches*;
> each batch only EXTENDS the VUL of existing nodes with the new sequences (no full-DB re-mine) and then
> PROMOTES SHS->HS once minUtil is reached. It differs from RIncHusp in updating by the correct
> **MAX-measure**. This is a **high-coverage approximation** (not full mining): coverage depends on the
> buffer threshold θ=μ·minUtil. Creating nodes for entirely new patterns (frontier growth / pre-large)
> is a later extension.

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

**D_old (6 sequences):** minUtil = 29, maxReg = 3, θ(μ=0.4) = 12
- High utility (HS): `<(a)(b)>`=104, `<(b)>`=96  *(stable patterns)*
- Buffer SHS [12, 29): `<(a)(d e)>`=22, `<(d e)>`=18, `<(a)(d)>`=14, `<(a)(e)>`=12, `<(a)>`=12
  *(the `{d,e}` family is TRACKED from D_old, not yet at minUtil)*

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
| `<(e)>` | `<(5)>` | 128 | 3 | emerging | no (see §5.5) |

-> P-RIncHUSP reaches **7/8 = 87.5%** (high coverage); only `<(e)>` is missing.

## 5. Where each contribution is illustrated

1. **Accumulative incremental update (Lemma 1):** §3 table — ΔD is appended at the tail, every cell accumulates.
2. **Regularity-based pruning (Corollary 2):** `<(c)>` has utility = 40·4 = **160 ≥ minUtil**
   but appears only in SID 2 -> maxPer = max(3, 9−2) = 7 > maxReg = 5 -> **rejected**. A high-utility
   pattern is rejected *purely due to non-regularity*.
3. **MAX utility measure (bug fix):** sequence 8 contains d twice (d:2 and d:10). `<(d)>`
   takes the maximum match = 50 (not 50+10) -> total utility **160**, not 170 as with naive
   accumulation. This is also where P-RIncHUSP BEATS RIncHusp: with the correct measure, `<(d)>` is
   promoted; RIncHusp updates naively / matches greedily and therefore misses this pattern.
4. **Buffer + SHS->HS promotion (core contribution, maintenance mechanism):** the `{d,e}` family in
   D_old has utility BELOW minUtil but is KEPT in the SHS buffer (§4); in D_new it surges and is
   **promoted** to HS. The lower the adaptive buffer threshold θ=μ·minUtil, the more promising
   patterns are kept -> the higher the coverage (the basis of the advantage over RIncHusp).
5. **Approximation limit (stated honestly):** `<(e)>` is pruned at the initialization phase because the
   upper bound sumPeu = 8 < θ₀ = 12 (item e is always at the end of an event -> remaining utility = 0).
   No node is tracked -> it cannot be promoted. Recovering patterns of this kind requires **frontier
   growth / pre-large** (creating nodes for prefixes that newly become promising) — an extension, not yet implemented.
6. **Directed DEUCS (I and S):** `<(d e)>` co-occurs within the same event (DEUCS_I);
   `<(a)(b)>`, `<(a)(d)>` follow the sequence order (DEUCS_S).

## 6. Correctness test + algorithm contrast (δ=0.10, ρ=0.6, batch 6/3)

| Algorithm | mechanism | HS | coverage | note |
|---|---|--:|--:|---|
| **RHusp** (oracle) | exact re-mine | 8 | 100% | reference patterns (== AlgoRHUSPMiner) |
| **P-RIncHUSP** (proposed) | maintain SHS + promote, parallel | 7 | **87.5%** | high coverage; correct update |
| Proposed-sequential (T=1) | as above, 1 thread | 7 | 87.5% | same result, speedup reference point |
| **RIncHusp** [Ishita2022] | incremental, naive update | 6 | **75%** | misses `<(d)>` due to greedy match |

P-RIncHUSP and RIncHusp both track the `{d,e}` family from the D_old buffer, but P-RIncHUSP updates by
the correct **max-measure** and so promotes `<(d)>`; RIncHusp updates naively / matches greedily, gets
the wrong value, and misses it. Both miss `<(e)>` (approximation limit, §5.5). *No pattern is a false positive.*

## 7. Reproduction

```bash
javac -d out src/algorithms/*.java
java -cp out algorithms.RunIncremental \
     datasets/example_seq.txt datasets/example_eui.txt 0.10 0.6 "0.667,0.333"
```
