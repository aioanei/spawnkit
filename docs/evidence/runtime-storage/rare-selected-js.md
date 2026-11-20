# SpawnKit runtime storage benchmark
platform=Kotlin/JS on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 1679 | n/a |
| query dense (position+velocity) | 100 | 1057 | n/a |
| query sparse (marker) | 100 | 629 | n/a |
| query rare (link, 2%) | 100 | 1166 | n/a |
| get | 100 | 30.3 | n/a |
| step steady | 100 | 14482 | n/a |
| step churn (size/10 edits) | 100 | 19823 | n/a |
| snapshot | 100 | 22940 | n/a |
| restore | 100 | 25440 | n/a |
| spawn+attach | 1000 | 1684 | n/a |
| query dense (position+velocity) | 1000 | 10480 | n/a |
| query sparse (marker) | 1000 | 4990 | n/a |
| query rare (link, 2%) | 1000 | 1260 | n/a |
| get | 1000 | 39.6 | n/a |
| step steady | 1000 | 167350 | n/a |
| step churn (size/10 edits) | 1000 | 279430 | n/a |
| snapshot | 1000 | 228100 | n/a |
| restore | 1000 | 266850 | n/a |
| spawn+attach | 10000 | 2102 | n/a |
| query dense (position+velocity) | 10000 | 134300 | n/a |
| query sparse (marker) | 10000 | 72500 | n/a |
| query rare (link, 2%) | 10000 | 11200 | n/a |
| get | 10000 | 52.6 | n/a |
| step steady | 10000 | 2335150 | n/a |
| step churn (size/10 edits) | 10000 | 3963200 | n/a |
| snapshot | 10000 | 3197800 | n/a |
| restore | 10000 | 4056000 | n/a |
sink=716892676
