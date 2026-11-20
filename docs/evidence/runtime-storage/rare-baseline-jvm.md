# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 64.0 | 239 |
| query dense (position+velocity) | 100 | 2674 | 3360 |
| query sparse (marker) | 100 | 1704 | 1360 |
| query rare (link, 2%) | 100 | 1458 | 192 |
| get | 100 | 8.1 | 0.0 |
| step steady | 100 | 5958 | 4840 |
| step churn (size/10 edits) | 100 | 8563 | 10017 |
| snapshot | 100 | 8340 | 40599 |
| restore | 100 | 8640 | 39650 |
| spawn+attach | 1000 | 62.9 | 287 |
| query dense (position+velocity) | 1000 | 31745 | 75946 |
| query sparse (marker) | 1000 | 18385 | 41490 |
| query rare (link, 2%) | 1000 | 16240 | 28930 |
| get | 1000 | 9.0 | 13.9 |
| step steady | 1000 | 68245 | 139585 |
| step churn (size/10 edits) | 1000 | 102290 | 218057 |
| snapshot | 1000 | 86900 | 419648 |
| restore | 1000 | 83900 | 430312 |
| spawn+attach | 10000 | 74.0 | 296 |
| query dense (position+velocity) | 10000 | 359000 | 826632 |
| query sparse (marker) | 10000 | 270900 | 439536 |
| query rare (link, 2%) | 10000 | 192650 | 326232 |
| get | 10000 | 11.5 | 15.8 |
| step steady | 10000 | 892200 | 1516284 |
| step churn (size/10 edits) | 10000 | 1164600 | 2356928 |
| snapshot | 10000 | 1514600 | 4194576 |
| restore | 10000 | 875400 | 4363968 |
sink=716892676
