# SpawnKit runtime storage benchmark
platform=native (WINDOWS X64, debug=false) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 289 | n/a |
| query dense (position+velocity) | 100 | 3658 | n/a |
| query sparse (marker) | 100 | 1409 | n/a |
| get | 100 | 56.9 | n/a |
| step steady | 100 | 13848 | n/a |
| step churn (size/10 edits) | 100 | 20128 | n/a |
| snapshot | 100 | 49100 | n/a |
| restore | 100 | 50400 | n/a |
| spawn+attach | 1000 | 279 | n/a |
| query dense (position+velocity) | 1000 | 34465 | n/a |
| query sparse (marker) | 1000 | 13410 | n/a |
| get | 1000 | 56.7 | n/a |
| step steady | 1000 | 132270 | n/a |
| step churn (size/10 edits) | 1000 | 218310 | n/a |
| snapshot | 1000 | 494350 | n/a |
| restore | 1000 | 514900 | n/a |
| spawn+attach | 10000 | 295 | n/a |
| query dense (position+velocity) | 10000 | 341750 | n/a |
| query sparse (marker) | 10000 | 135050 | n/a |
| get | 10000 | 57.4 | n/a |
| step steady | 10000 | 1331350 | n/a |
| step churn (size/10 edits) | 10000 | 2244700 | n/a |
| snapshot | 10000 | 6635600 | n/a |
| restore | 10000 | 5933700 | n/a |
sink=716864776
