# SpawnKit runtime storage benchmark
platform=Kotlin/JS on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 1708 | n/a |
| query dense (position+velocity) | 100 | 1064 | n/a |
| query sparse (marker) | 100 | 624 | n/a |
| get | 100 | 46.8 | n/a |
| step steady | 100 | 16222 | n/a |
| step churn (size/10 edits) | 100 | 22260 | n/a |
| snapshot | 100 | 25200 | n/a |
| restore | 100 | 34575 | n/a |
| spawn+attach | 1000 | 1958 | n/a |
| query dense (position+velocity) | 1000 | 10845 | n/a |
| query sparse (marker) | 1000 | 4840 | n/a |
| get | 1000 | 60.1 | n/a |
| step steady | 1000 | 198475 | n/a |
| step churn (size/10 edits) | 1000 | 299730 | n/a |
| snapshot | 1000 | 277300 | n/a |
| restore | 1000 | 288600 | n/a |
| spawn+attach | 10000 | 2236 | n/a |
| query dense (position+velocity) | 10000 | 124400 | n/a |
| query sparse (marker) | 10000 | 107150 | n/a |
| get | 10000 | 57.1 | n/a |
| step steady | 10000 | 2765750 | n/a |
| step churn (size/10 edits) | 10000 | 5918300 | n/a |
| snapshot | 10000 | 4469500 | n/a |
| restore | 10000 | 3937300 | n/a |
sink=716864776
