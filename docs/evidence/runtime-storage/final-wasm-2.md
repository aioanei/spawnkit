# SpawnKit runtime storage benchmark
platform=Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 109 | n/a |
| query dense (position+velocity) | 100 | 749 | n/a |
| query sparse (marker) | 100 | 367 | n/a |
| get | 100 | 12.2 | n/a |
| step steady | 100 | 3492 | n/a |
| step churn (size/10 edits) | 100 | 6135 | n/a |
| snapshot | 100 | 15430 | n/a |
| restore | 100 | 15350 | n/a |
| spawn+attach | 1000 | 94.5 | n/a |
| query dense (position+velocity) | 1000 | 7490 | n/a |
| query sparse (marker) | 1000 | 3420 | n/a |
| get | 1000 | 12.1 | n/a |
| step steady | 1000 | 33755 | n/a |
| step churn (size/10 edits) | 1000 | 65290 | n/a |
| snapshot | 1000 | 156750 | n/a |
| restore | 1000 | 155550 | n/a |
| spawn+attach | 10000 | 101 | n/a |
| query dense (position+velocity) | 10000 | 111550 | n/a |
| query sparse (marker) | 10000 | 45050 | n/a |
| get | 10000 | 13.1 | n/a |
| step steady | 10000 | 430100 | n/a |
| step churn (size/10 edits) | 10000 | 800500 | n/a |
| snapshot | 10000 | 2162800 | n/a |
| restore | 10000 | 2618700 | n/a |
sink=716864776
