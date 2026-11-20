# SpawnKit runtime storage benchmark
platform=Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 145 | n/a |
| query dense (position+velocity) | 100 | 874 | n/a |
| query sparse (marker) | 100 | 369 | n/a |
| get | 100 | 12.2 | n/a |
| step steady | 100 | 3470 | n/a |
| step churn (size/10 edits) | 100 | 6136 | n/a |
| snapshot | 100 | 15110 | n/a |
| restore | 100 | 15270 | n/a |
| spawn+attach | 1000 | 93.7 | n/a |
| query dense (position+velocity) | 1000 | 7465 | n/a |
| query sparse (marker) | 1000 | 3370 | n/a |
| get | 1000 | 12.1 | n/a |
| step steady | 1000 | 33655 | n/a |
| step churn (size/10 edits) | 1000 | 65230 | n/a |
| snapshot | 1000 | 158950 | n/a |
| restore | 1000 | 157450 | n/a |
| spawn+attach | 10000 | 101 | n/a |
| query dense (position+velocity) | 10000 | 112150 | n/a |
| query sparse (marker) | 10000 | 41550 | n/a |
| get | 10000 | 13.1 | n/a |
| step steady | 10000 | 414400 | n/a |
| step churn (size/10 edits) | 10000 | 802900 | n/a |
| snapshot | 10000 | 1883600 | n/a |
| restore | 10000 | 5771900 | n/a |
sink=716864776
