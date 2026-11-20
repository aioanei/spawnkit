# SpawnKit runtime storage benchmark
platform=Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 117 | n/a |
| query dense (position+velocity) | 100 | 748 | n/a |
| query sparse (marker) | 100 | 368 | n/a |
| get | 100 | 12.3 | n/a |
| step steady | 100 | 3641 | n/a |
| step churn (size/10 edits) | 100 | 6391 | n/a |
| snapshot | 100 | 18085 | n/a |
| restore | 100 | 14750 | n/a |
| spawn+attach | 1000 | 104 | n/a |
| query dense (position+velocity) | 1000 | 7515 | n/a |
| query sparse (marker) | 1000 | 3375 | n/a |
| get | 1000 | 12.3 | n/a |
| step steady | 1000 | 35395 | n/a |
| step churn (size/10 edits) | 1000 | 67350 | n/a |
| snapshot | 1000 | 187450 | n/a |
| restore | 1000 | 162500 | n/a |
| spawn+attach | 10000 | 113 | n/a |
| query dense (position+velocity) | 10000 | 107200 | n/a |
| query sparse (marker) | 10000 | 44100 | n/a |
| get | 10000 | 13.8 | n/a |
| step steady | 10000 | 471100 | n/a |
| step churn (size/10 edits) | 10000 | 912200 | n/a |
| snapshot | 10000 | 2705400 | n/a |
| restore | 10000 | 5383200 | n/a |
sink=716864776
