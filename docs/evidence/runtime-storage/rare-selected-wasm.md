# SpawnKit runtime storage benchmark
platform=Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 102 | n/a |
| query dense (position+velocity) | 100 | 749 | n/a |
| query sparse (marker) | 100 | 368 | n/a |
| query rare (link, 2%) | 100 | 100 | n/a |
| get | 100 | 12.2 | n/a |
| step steady | 100 | 3478 | n/a |
| step churn (size/10 edits) | 100 | 6144 | n/a |
| snapshot | 100 | 16405 | n/a |
| restore | 100 | 15145 | n/a |
| spawn+attach | 1000 | 95.2 | n/a |
| query dense (position+velocity) | 1000 | 7420 | n/a |
| query sparse (marker) | 1000 | 3340 | n/a |
| query rare (link, 2%) | 1000 | 895 | n/a |
| get | 1000 | 12.4 | n/a |
| step steady | 1000 | 33735 | n/a |
| step churn (size/10 edits) | 1000 | 65260 | n/a |
| snapshot | 1000 | 148850 | n/a |
| restore | 1000 | 161700 | n/a |
| spawn+attach | 10000 | 103 | n/a |
| query dense (position+velocity) | 10000 | 111350 | n/a |
| query sparse (marker) | 10000 | 45800 | n/a |
| query rare (link, 2%) | 10000 | 10300 | n/a |
| get | 10000 | 13.3 | n/a |
| step steady | 10000 | 426250 | n/a |
| step churn (size/10 edits) | 10000 | 853200 | n/a |
| snapshot | 10000 | 2579100 | n/a |
| restore | 10000 | 2986100 | n/a |
sink=716892676
