# SpawnKit runtime storage benchmark
platform=native (WINDOWS X64, debug=false) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 286 | n/a |
| query dense (position+velocity) | 100 | 3532 | n/a |
| query sparse (marker) | 100 | 1362 | n/a |
| get | 100 | 65.9 | n/a |
| step steady | 100 | 15094 | n/a |
| step churn (size/10 edits) | 100 | 20662 | n/a |
| snapshot | 100 | 54860 | n/a |
| restore | 100 | 50445 | n/a |
| spawn+attach | 1000 | 280 | n/a |
| query dense (position+velocity) | 1000 | 33620 | n/a |
| query sparse (marker) | 1000 | 13265 | n/a |
| get | 1000 | 67.1 | n/a |
| step steady | 1000 | 146950 | n/a |
| step churn (size/10 edits) | 1000 | 233360 | n/a |
| snapshot | 1000 | 539200 | n/a |
| restore | 1000 | 513450 | n/a |
| spawn+attach | 10000 | 297 | n/a |
| query dense (position+velocity) | 10000 | 340700 | n/a |
| query sparse (marker) | 10000 | 133300 | n/a |
| get | 10000 | 67.2 | n/a |
| step steady | 10000 | 1469800 | n/a |
| step churn (size/10 edits) | 10000 | 2431500 | n/a |
| snapshot | 10000 | 7941500 | n/a |
| restore | 10000 | 5988000 | n/a |
sink=716864776
