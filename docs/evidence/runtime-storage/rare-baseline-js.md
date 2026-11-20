# SpawnKit runtime storage benchmark
platform=Kotlin/JS on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 1946 | n/a |
| query dense (position+velocity) | 100 | 8940 | n/a |
| query sparse (marker) | 100 | 4429 | n/a |
| query rare (link, 2%) | 100 | 3729 | n/a |
| get | 100 | 59 | n/a |
| step steady | 100 | 31333 | n/a |
| step churn (size/10 edits) | 100 | 38243 | n/a |
| snapshot | 100 | 29620 | n/a |
| restore | 100 | 34545 | n/a |
| spawn+attach | 1000 | 1818 | n/a |
| query dense (position+velocity) | 1000 | 97045 | n/a |
| query sparse (marker) | 1000 | 44445 | n/a |
| query rare (link, 2%) | 1000 | 39810 | n/a |
| get | 1000 | 119 | n/a |
| step steady | 1000 | 351425 | n/a |
| step churn (size/10 edits) | 1000 | 537640 | n/a |
| snapshot | 1000 | 360700 | n/a |
| restore | 1000 | 339150 | n/a |
| spawn+attach | 10000 | 2054 | n/a |
| query dense (position+velocity) | 10000 | 1373900 | n/a |
| query sparse (marker) | 10000 | 713100 | n/a |
| query rare (link, 2%) | 10000 | 469100 | n/a |
| get | 10000 | 113 | n/a |
| step steady | 10000 | 5155800 | n/a |
| step churn (size/10 edits) | 10000 | 6187500 | n/a |
| snapshot | 10000 | 3955300 | n/a |
| restore | 10000 | 4953300 | n/a |
sink=716892676
