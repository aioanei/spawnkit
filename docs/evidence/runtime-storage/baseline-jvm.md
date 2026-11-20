# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 110 | 239 |
| query dense (position+velocity) | 100 | 2787 | 3360 |
| query sparse (marker) | 100 | 1796 | 1360 |
| get | 100 | 8.2 | 0.0 |
| step steady | 100 | 6434 | 4840 |
| step churn (size/10 edits) | 100 | 9159 | 10017 |
| snapshot | 100 | 12915 | 40847 |
| restore | 100 | 17790 | 44458 |
| spawn+attach | 1000 | 59.6 | 287 |
| query dense (position+velocity) | 1000 | 32480 | 75946 |
| query sparse (marker) | 1000 | 19665 | 41490 |
| get | 1000 | 9.0 | 13.9 |
| step steady | 1000 | 70805 | 139585 |
| step churn (size/10 edits) | 1000 | 98520 | 218057 |
| snapshot | 1000 | 89350 | 419720 |
| restore | 1000 | 190600 | 476520 |
| spawn+attach | 10000 | 95.9 | 296 |
| query dense (position+velocity) | 10000 | 472100 | 826632 |
| query sparse (marker) | 10000 | 319150 | 439536 |
| get | 10000 | 14.7 | 15.8 |
| step steady | 10000 | 874350 | 1516284 |
| step churn (size/10 edits) | 10000 | 1424500 | 2356928 |
| snapshot | 10000 | 1134600 | 4194648 |
| restore | 10000 | 978300 | 4363968 |
sink=716864776
