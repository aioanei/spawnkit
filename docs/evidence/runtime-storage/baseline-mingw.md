# SpawnKit runtime storage benchmark
platform=native (WINDOWS X64, debug=false) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 467 | n/a |
| query dense (position+velocity) | 100 | 15928 | n/a |
| query sparse (marker) | 100 | 7135 | n/a |
| get | 100 | 106 | n/a |
| step steady | 100 | 37900 | n/a |
| step churn (size/10 edits) | 100 | 48449 | n/a |
| snapshot | 100 | 89505 | n/a |
| restore | 100 | 73785 | n/a |
| spawn+attach | 1000 | 469 | n/a |
| query dense (position+velocity) | 1000 | 165155 | n/a |
| query sparse (marker) | 1000 | 77420 | n/a |
| get | 1000 | 108 | n/a |
| step steady | 1000 | 415400 | n/a |
| step churn (size/10 edits) | 1000 | 573980 | n/a |
| snapshot | 1000 | 994800 | n/a |
| restore | 1000 | 740950 | n/a |
| spawn+attach | 10000 | 552 | n/a |
| query dense (position+velocity) | 10000 | 1911950 | n/a |
| query sparse (marker) | 10000 | 948100 | n/a |
| get | 10000 | 128 | n/a |
| step steady | 10000 | 4710250 | n/a |
| step churn (size/10 edits) | 10000 | 6365500 | n/a |
| snapshot | 10000 | 13508300 | n/a |
| restore | 10000 | 8622600 | n/a |
sink=716864776
