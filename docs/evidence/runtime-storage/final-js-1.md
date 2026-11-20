# SpawnKit runtime storage benchmark
platform=Kotlin/JS on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 1769 | n/a |
| query dense (position+velocity) | 100 | 1040 | n/a |
| query sparse (marker) | 100 | 997 | n/a |
| get | 100 | 31 | n/a |
| step steady | 100 | 15697 | n/a |
| step churn (size/10 edits) | 100 | 22324 | n/a |
| snapshot | 100 | 20515 | n/a |
| restore | 100 | 25210 | n/a |
| spawn+attach | 1000 | 2087 | n/a |
| query dense (position+velocity) | 1000 | 9900 | n/a |
| query sparse (marker) | 1000 | 4895 | n/a |
| get | 1000 | 46.6 | n/a |
| step steady | 1000 | 180610 | n/a |
| step churn (size/10 edits) | 1000 | 303010 | n/a |
| snapshot | 1000 | 225800 | n/a |
| restore | 1000 | 249950 | n/a |
| spawn+attach | 10000 | 2158 | n/a |
| query dense (position+velocity) | 10000 | 202350 | n/a |
| query sparse (marker) | 10000 | 104550 | n/a |
| get | 10000 | 64.4 | n/a |
| step steady | 10000 | 2323300 | n/a |
| step churn (size/10 edits) | 10000 | 4227200 | n/a |
| snapshot | 10000 | 3916700 | n/a |
| restore | 10000 | 3247500 | n/a |
sink=716864776
