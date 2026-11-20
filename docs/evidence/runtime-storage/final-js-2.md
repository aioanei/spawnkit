# SpawnKit runtime storage benchmark
platform=Kotlin/JS on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 1796 | n/a |
| query dense (position+velocity) | 100 | 1020 | n/a |
| query sparse (marker) | 100 | 608 | n/a |
| get | 100 | 54.6 | n/a |
| step steady | 100 | 15767 | n/a |
| step churn (size/10 edits) | 100 | 19878 | n/a |
| snapshot | 100 | 22430 | n/a |
| restore | 100 | 24690 | n/a |
| spawn+attach | 1000 | 1658 | n/a |
| query dense (position+velocity) | 1000 | 9780 | n/a |
| query sparse (marker) | 1000 | 5680 | n/a |
| get | 1000 | 87.3 | n/a |
| step steady | 1000 | 173405 | n/a |
| step churn (size/10 edits) | 1000 | 250950 | n/a |
| snapshot | 1000 | 250600 | n/a |
| restore | 1000 | 309000 | n/a |
| spawn+attach | 10000 | 1974 | n/a |
| query dense (position+velocity) | 10000 | 153200 | n/a |
| query sparse (marker) | 10000 | 101650 | n/a |
| get | 10000 | 67.5 | n/a |
| step steady | 10000 | 2752800 | n/a |
| step churn (size/10 edits) | 10000 | 4096500 | n/a |
| snapshot | 10000 | 3313500 | n/a |
| restore | 10000 | 3691800 | n/a |
sink=716864776
