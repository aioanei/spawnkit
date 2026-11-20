# SpawnKit runtime storage benchmark
platform=native (WINDOWS X64, debug=false) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 478 | n/a |
| query dense (position+velocity) | 100 | 14183 | n/a |
| query sparse (marker) | 100 | 7193 | n/a |
| query rare (link, 2%) | 100 | 4983 | n/a |
| get | 100 | 110 | n/a |
| step steady | 100 | 38465 | n/a |
| step churn (size/10 edits) | 100 | 47355 | n/a |
| snapshot | 100 | 89600 | n/a |
| restore | 100 | 72520 | n/a |
| spawn+attach | 1000 | 461 | n/a |
| query dense (position+velocity) | 1000 | 153455 | n/a |
| query sparse (marker) | 1000 | 75560 | n/a |
| query rare (link, 2%) | 1000 | 53700 | n/a |
| get | 1000 | 111 | n/a |
| step steady | 1000 | 417515 | n/a |
| step churn (size/10 edits) | 1000 | 576940 | n/a |
| snapshot | 1000 | 1036250 | n/a |
| restore | 1000 | 734850 | n/a |
| spawn+attach | 10000 | 583 | n/a |
| query dense (position+velocity) | 10000 | 1821300 | n/a |
| query sparse (marker) | 10000 | 948500 | n/a |
| query rare (link, 2%) | 10000 | 586200 | n/a |
| get | 10000 | 120 | n/a |
| step steady | 10000 | 4794200 | n/a |
| step churn (size/10 edits) | 10000 | 6569300 | n/a |
| snapshot | 10000 | 14416000 | n/a |
| restore | 10000 | 13555000 | n/a |
sink=716892676
