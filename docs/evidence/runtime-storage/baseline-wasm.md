# SpawnKit runtime storage benchmark
platform=Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 164 | n/a |
| query dense (position+velocity) | 100 | 4948 | n/a |
| query sparse (marker) | 100 | 2577 | n/a |
| get | 100 | 25.4 | n/a |
| step steady | 100 | 12761 | n/a |
| step churn (size/10 edits) | 100 | 16028 | n/a |
| snapshot | 100 | 46230 | n/a |
| restore | 100 | 23345 | n/a |
| spawn+attach | 1000 | 141 | n/a |
| query dense (position+velocity) | 1000 | 59230 | n/a |
| query sparse (marker) | 1000 | 29850 | n/a |
| get | 1000 | 36.8 | n/a |
| step steady | 1000 | 142465 | n/a |
| step churn (size/10 edits) | 1000 | 192470 | n/a |
| snapshot | 1000 | 513800 | n/a |
| restore | 1000 | 244550 | n/a |
| spawn+attach | 10000 | 185 | n/a |
| query dense (position+velocity) | 10000 | 891500 | n/a |
| query sparse (marker) | 10000 | 371500 | n/a |
| get | 10000 | 38.2 | n/a |
| step steady | 10000 | 1757400 | n/a |
| step churn (size/10 edits) | 10000 | 2451800 | n/a |
| snapshot | 10000 | 8551900 | n/a |
| restore | 10000 | 3675700 | n/a |
sink=716864776
