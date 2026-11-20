# SpawnKit runtime storage benchmark
platform=native (WINDOWS X64, debug=false) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 293 | n/a |
| query dense (position+velocity) | 100 | 3553 | n/a |
| query sparse (marker) | 100 | 1467 | n/a |
| get | 100 | 57.4 | n/a |
| step steady | 100 | 13487 | n/a |
| step churn (size/10 edits) | 100 | 19833 | n/a |
| snapshot | 100 | 50340 | n/a |
| restore | 100 | 53445 | n/a |
| spawn+attach | 1000 | 283 | n/a |
| query dense (position+velocity) | 1000 | 33710 | n/a |
| query sparse (marker) | 1000 | 14020 | n/a |
| get | 1000 | 57.5 | n/a |
| step steady | 1000 | 132610 | n/a |
| step churn (size/10 edits) | 1000 | 218170 | n/a |
| snapshot | 1000 | 490800 | n/a |
| restore | 1000 | 519500 | n/a |
| spawn+attach | 10000 | 320 | n/a |
| query dense (position+velocity) | 10000 | 369300 | n/a |
| query sparse (marker) | 10000 | 139500 | n/a |
| get | 10000 | 59.8 | n/a |
| step steady | 10000 | 1387050 | n/a |
| step churn (size/10 edits) | 10000 | 2328100 | n/a |
| snapshot | 10000 | 6703500 | n/a |
| restore | 10000 | 6473800 | n/a |
sink=716864776
