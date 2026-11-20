# SpawnKit runtime storage benchmark
platform=Kotlin/JS on Node.js kotlin=2.4.21 samples=9
| case | entities | ns/op | bytes/op |
|---|---:|---:|---:|
| spawn+attach | 100 | 3353 | n/a |
| query dense (position+velocity) | 100 | 17184 | n/a |
| query sparse (marker) | 100 | 6686 | n/a |
| get | 100 | 98.1 | n/a |
| step steady | 100 | 57926 | n/a |
| step churn (size/10 edits) | 100 | 56095 | n/a |
| snapshot | 100 | 54815 | n/a |
| restore | 100 | 52545 | n/a |
| spawn+attach | 1000 | 2998 | n/a |
| query dense (position+velocity) | 1000 | 226445 | n/a |
| query sparse (marker) | 1000 | 102535 | n/a |
| get | 1000 | 172 | n/a |
| step steady | 1000 | 588120 | n/a |
| step churn (size/10 edits) | 1000 | 802610 | n/a |
| snapshot | 1000 | 595250 | n/a |
| restore | 1000 | 478700 | n/a |
| spawn+attach | 10000 | 2269 | n/a |
| query dense (position+velocity) | 10000 | 1537650 | n/a |
| query sparse (marker) | 10000 | 798200 | n/a |
| get | 10000 | 109 | n/a |
| step steady | 10000 | 5339450 | n/a |
| step churn (size/10 edits) | 10000 | 6723500 | n/a |
| snapshot | 10000 | 4674900 | n/a |
| restore | 10000 | 4966000 | n/a |
sink=716864776
