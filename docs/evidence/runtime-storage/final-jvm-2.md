# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 38.0 | 213 |
| query dense (position+velocity) | 100 | 474 | 352 |
| query sparse (marker) | 100 | 364 | 192 |
| get | 100 | 2.9 | 0.0 |
| step steady | 100 | 1254 | 664 |
| step churn (size/10 edits) | 100 | 2229 | 1880 |
| snapshot | 100 | 12400 | 38946 |
| restore | 100 | 10210 | 24002 |
| spawn+attach | 1000 | 29.3 | 198 |
| query dense (position+velocity) | 1000 | 3900 | 2754 |
| query sparse (marker) | 1000 | 2060 | 1090 |
| get | 1000 | 2.9 | 0.0 |
| step steady | 1000 | 10235 | 3913 |
| step churn (size/10 edits) | 1000 | 18670 | 18662 |
| snapshot | 1000 | 149900 | 408600 |
| restore | 1000 | 55100 | 256408 |
| spawn+attach | 10000 | 32.4 | 223 |
| query dense (position+velocity) | 10000 | 44100 | 26776 |
| query sparse (marker) | 10000 | 29200 | 10112 |
| get | 10000 | 6.7 | 0.0 |
| step steady | 10000 | 118900 | 36924 |
| step churn (size/10 edits) | 10000 | 246100 | 246272 |
| snapshot | 10000 | 806600 | 4113536 |
| restore | 10000 | 580400 | 2585824 |
sink=716864776
