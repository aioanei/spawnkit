# SpawnKit runtime storage benchmark
platform=jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 47.0 | 213 |
| query dense (position+velocity) | 100 | 479 | 352 |
| query sparse (marker) | 100 | 368 | 192 |
| query rare (link, 2%) | 100 | 211 | 120 |
| get | 100 | 2.8 | 0.0 |
| step steady | 100 | 1931 | 664 |
| step churn (size/10 edits) | 100 | 2499 | 1880 |
| snapshot | 100 | 16660 | 38946 |
| restore | 100 | 17480 | 24002 |
| spawn+attach | 1000 | 46.2 | 198 |
| query dense (position+velocity) | 1000 | 4655 | 2754 |
| query sparse (marker) | 1000 | 2880 | 1090 |
| query rare (link, 2%) | 1000 | 250 | 266 |
| get | 1000 | 3.8 | 0.0 |
| step steady | 1000 | 11535 | 3913 |
| step churn (size/10 edits) | 1000 | 23190 | 18662 |
| snapshot | 1000 | 150800 | 408600 |
| restore | 1000 | 168550 | 256632 |
| spawn+attach | 10000 | 32.9 | 223 |
| query dense (position+velocity) | 10000 | 131000 | 26776 |
| query sparse (marker) | 10000 | 116400 | 10112 |
| query rare (link, 2%) | 10000 | 5450 | 1728 |
| get | 10000 | 3.6 | 0.0 |
| step steady | 10000 | 149700 | 36924 |
| step churn (size/10 edits) | 10000 | 289000 | 246272 |
| snapshot | 10000 | 850000 | 4113536 |
| restore | 10000 | 1257300 | 2585856 |
sink=716892676
