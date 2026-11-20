# SpawnKit runtime storage benchmark
platform=native (WINDOWS X64, debug=false) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 286 | n/a |
| query dense (position+velocity) | 100 | 3457 | n/a |
| query sparse (marker) | 100 | 1379 | n/a |
| query rare (link, 2%) | 100 | 159 | n/a |
| get | 100 | 57.1 | n/a |
| step steady | 100 | 13672 | n/a |
| step churn (size/10 edits) | 100 | 19530 | n/a |
| snapshot | 100 | 49960 | n/a |
| restore | 100 | 52615 | n/a |
| spawn+attach | 1000 | 278 | n/a |
| query dense (position+velocity) | 1000 | 34575 | n/a |
| query sparse (marker) | 1000 | 13470 | n/a |
| query rare (link, 2%) | 1000 | 1150 | n/a |
| get | 1000 | 57.3 | n/a |
| step steady | 1000 | 134490 | n/a |
| step churn (size/10 edits) | 1000 | 223470 | n/a |
| snapshot | 1000 | 497150 | n/a |
| restore | 1000 | 539100 | n/a |
| spawn+attach | 10000 | 310 | n/a |
| query dense (position+velocity) | 10000 | 339350 | n/a |
| query sparse (marker) | 10000 | 135300 | n/a |
| query rare (link, 2%) | 10000 | 11550 | n/a |
| get | 10000 | 57.6 | n/a |
| step steady | 10000 | 1352100 | n/a |
| step churn (size/10 edits) | 10000 | 2294500 | n/a |
| snapshot | 10000 | 6488500 | n/a |
| restore | 10000 | 5720500 | n/a |
sink=716892676
