# SpawnKit runtime storage benchmark
platform=Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 155 | n/a |
| query dense (position+velocity) | 100 | 4857 | n/a |
| query sparse (marker) | 100 | 2520 | n/a |
| query rare (link, 2%) | 100 | 2033 | n/a |
| get | 100 | 24.9 | n/a |
| step steady | 100 | 11468 | n/a |
| step churn (size/10 edits) | 100 | 15466 | n/a |
| snapshot | 100 | 33000 | n/a |
| restore | 100 | 29885 | n/a |
| spawn+attach | 1000 | 205 | n/a |
| query dense (position+velocity) | 1000 | 55250 | n/a |
| query sparse (marker) | 1000 | 27815 | n/a |
| query rare (link, 2%) | 1000 | 21320 | n/a |
| get | 1000 | 27.1 | n/a |
| step steady | 1000 | 125525 | n/a |
| step churn (size/10 edits) | 1000 | 187410 | n/a |
| snapshot | 1000 | 475700 | n/a |
| restore | 1000 | 218700 | n/a |
| spawn+attach | 10000 | 179 | n/a |
| query dense (position+velocity) | 10000 | 755950 | n/a |
| query sparse (marker) | 10000 | 398250 | n/a |
| query rare (link, 2%) | 10000 | 271850 | n/a |
| get | 10000 | 37.2 | n/a |
| step steady | 10000 | 1668400 | n/a |
| step churn (size/10 edits) | 10000 | 2327000 | n/a |
| snapshot | 10000 | 8144100 | n/a |
| restore | 10000 | 7385100 | n/a |
sink=716892676
