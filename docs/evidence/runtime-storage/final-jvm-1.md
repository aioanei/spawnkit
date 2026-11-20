# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 38.0 | 213 |
| query dense (position+velocity) | 100 | 472 | 352 |
| query sparse (marker) | 100 | 403 | 192 |
| get | 100 | 18.5 | 0.0 |
| step steady | 100 | 1224 | 664 |
| step churn (size/10 edits) | 100 | 2086 | 1880 |
| snapshot | 100 | 12125 | 38946 |
| restore | 100 | 10195 | 24002 |
| spawn+attach | 1000 | 29.6 | 198 |
| query dense (position+velocity) | 1000 | 3910 | 2754 |
| query sparse (marker) | 1000 | 1895 | 1090 |
| get | 1000 | 2.9 | 0.0 |
| step steady | 1000 | 10165 | 3961 |
| step churn (size/10 edits) | 1000 | 17740 | 18662 |
| snapshot | 1000 | 147650 | 408600 |
| restore | 1000 | 93800 | 256632 |
| spawn+attach | 10000 | 32.5 | 223 |
| query dense (position+velocity) | 10000 | 66100 | 26776 |
| query sparse (marker) | 10000 | 29450 | 10112 |
| get | 10000 | 2.8 | 0.0 |
| step steady | 10000 | 147450 | 36924 |
| step churn (size/10 edits) | 10000 | 247400 | 246272 |
| snapshot | 10000 | 784300 | 4113536 |
| restore | 10000 | 602900 | 2585824 |
sink=716864776
