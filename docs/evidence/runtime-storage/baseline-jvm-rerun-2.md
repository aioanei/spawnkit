# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 110 | 239 |
| query dense (position+velocity) | 100 | 3278 | 3360 |
| query sparse (marker) | 100 | 2262 | 1360 |
| get | 100 | 15.9 | 0.0 |
| step steady | 100 | 6435 | 4840 |
| step churn (size/10 edits) | 100 | 9193 | 10017 |
| snapshot | 100 | 14280 | 40847 |
| restore | 100 | 17090 | 44458 |
| spawn+attach | 1000 | 64.4 | 287 |
| query dense (position+velocity) | 1000 | 32640 | 75946 |
| query sparse (marker) | 1000 | 19855 | 41490 |
| get | 1000 | 9.8 | 13.9 |
| step steady | 1000 | 72520 | 139585 |
| step churn (size/10 edits) | 1000 | 102840 | 218057 |
| snapshot | 1000 | 245200 | 419896 |
| restore | 1000 | 185100 | 476520 |
| spawn+attach | 10000 | 101 | 296 |
| query dense (position+velocity) | 10000 | 372600 | 826632 |
| query sparse (marker) | 10000 | 488600 | 439536 |
| get | 10000 | 11.6 | 15.8 |
| step steady | 10000 | 856700 | 1516284 |
| step churn (size/10 edits) | 10000 | 1428000 | 2356928 |
| snapshot | 10000 | 1177500 | 4194648 |
| restore | 10000 | 942100 | 4363968 |
sink=716864776
