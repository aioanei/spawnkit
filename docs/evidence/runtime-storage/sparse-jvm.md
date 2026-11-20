# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 38.0 | 213 |
| query dense (position+velocity) | 100 | 515 | 352 |
| query sparse (marker) | 100 | 376 | 192 |
| get | 100 | 2.8 | 0.0 |
| step steady | 100 | 1426 | 664 |
| step churn (size/10 edits) | 100 | 3481 | 1880 |
| snapshot | 100 | 11015 | 44514 |
| restore | 100 | 5885 | 23778 |
| spawn+attach | 1000 | 33.3 | 198 |
| query dense (position+velocity) | 1000 | 4150 | 2754 |
| query sparse (marker) | 1000 | 2380 | 1090 |
| get | 1000 | 3.0 | 0.0 |
| step steady | 1000 | 10495 | 3913 |
| step churn (size/10 edits) | 1000 | 18870 | 18662 |
| snapshot | 1000 | 115750 | 489200 |
| restore | 1000 | 66250 | 256408 |
| spawn+attach | 10000 | 35.1 | 223 |
| query dense (position+velocity) | 10000 | 108000 | 26776 |
| query sparse (marker) | 10000 | 34550 | 10112 |
| get | 10000 | 2.8 | 0.0 |
| step steady | 10000 | 173200 | 36924 |
| step churn (size/10 edits) | 10000 | 317300 | 246272 |
| snapshot | 10000 | 1389400 | 4953224 |
| restore | 10000 | 1161600 | 2585824 |
sink=716864776
