# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 113 | 239 |
| query dense (position+velocity) | 100 | 2936 | 3360 |
| query sparse (marker) | 100 | 1898 | 1360 |
| get | 100 | 12.9 | 0.0 |
| step steady | 100 | 8440 | 4840 |
| step churn (size/10 edits) | 100 | 12037 | 10065 |
| snapshot | 100 | 14090 | 40847 |
| restore | 100 | 11365 | 39650 |
| spawn+attach | 1000 | 101 | 287 |
| query dense (position+velocity) | 1000 | 39180 | 75946 |
| query sparse (marker) | 1000 | 28760 | 41490 |
| get | 1000 | 13.0 | 13.9 |
| step steady | 1000 | 82715 | 139585 |
| step churn (size/10 edits) | 1000 | 133110 | 218057 |
| snapshot | 1000 | 105600 | 419648 |
| restore | 1000 | 97350 | 430312 |
| spawn+attach | 10000 | 79.9 | 296 |
| query dense (position+velocity) | 10000 | 375850 | 826632 |
| query sparse (marker) | 10000 | 335850 | 439536 |
| get | 10000 | 15.4 | 15.8 |
| step steady | 10000 | 975850 | 1516284 |
| step churn (size/10 edits) | 10000 | 2131300 | 2356928 |
| snapshot | 10000 | 3069400 | 4194576 |
| restore | 10000 | 1489800 | 4363968 |
sink=716864776
