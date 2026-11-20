# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 46.0 | 213 |
| query dense (position+velocity) | 100 | 480 | 352 |
| query sparse (marker) | 100 | 348 | 192 |
| get | 100 | 2.8 | 0.0 |
| step steady | 100 | 1477 | 664 |
| step churn (size/10 edits) | 100 | 2245 | 1880 |
| snapshot | 100 | 11110 | 44514 |
| restore | 100 | 11555 | 24002 |
| spawn+attach | 1000 | 32.0 | 198 |
| query dense (position+velocity) | 1000 | 4320 | 2754 |
| query sparse (marker) | 1000 | 3095 | 1090 |
| get | 1000 | 3.0 | 0.0 |
| step steady | 1000 | 10260 | 3913 |
| step churn (size/10 edits) | 1000 | 19870 | 18662 |
| snapshot | 1000 | 107900 | 489200 |
| restore | 1000 | 74700 | 256408 |
| spawn+attach | 10000 | 33.8 | 223 |
| query dense (position+velocity) | 10000 | 96950 | 26776 |
| query sparse (marker) | 10000 | 51250 | 10112 |
| get | 10000 | 3.0 | 0.0 |
| step steady | 10000 | 255200 | 36924 |
| step churn (size/10 edits) | 10000 | 279800 | 246272 |
| snapshot | 10000 | 1136800 | 4953224 |
| restore | 10000 | 861200 | 2585824 |
sink=716864776
