# SpawnKit runtime storage benchmark
platform=Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 105 | n/a |
| query dense (position+velocity) | 100 | 745 | n/a |
| query sparse (marker) | 100 | 370 | n/a |
| get | 100 | 17.9 | n/a |
| step steady | 100 | 3640 | n/a |
| step churn (size/10 edits) | 100 | 6384 | n/a |
| snapshot | 100 | 18180 | n/a |
| restore | 100 | 16325 | n/a |
| spawn+attach | 1000 | 95.9 | n/a |
| query dense (position+velocity) | 1000 | 7395 | n/a |
| query sparse (marker) | 1000 | 3315 | n/a |
| get | 1000 | 12.6 | n/a |
| step steady | 1000 | 36170 | n/a |
| step churn (size/10 edits) | 1000 | 67210 | n/a |
| snapshot | 1000 | 213550 | n/a |
| restore | 1000 | 164250 | n/a |
| spawn+attach | 10000 | 105 | n/a |
| query dense (position+velocity) | 10000 | 132700 | n/a |
| query sparse (marker) | 10000 | 44250 | n/a |
| get | 10000 | 13.4 | n/a |
| step steady | 10000 | 427800 | n/a |
| step churn (size/10 edits) | 10000 | 837600 | n/a |
| snapshot | 10000 | 2370300 | n/a |
| restore | 10000 | 2686900 | n/a |
sink=716864776
