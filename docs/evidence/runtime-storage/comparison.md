### jvm (OpenJDK 64-Bit Server VM 21.0.6; Windows 11 amd64) kotlin=2.4.21 samples=9

| case | entities | baseline ns/op | sparse-set ns/op | speedup | baseline B/op | sparse-set B/op |
|---|---:|---:|---:|---:|---:|---:|
| spawn+attach | 100 | 110 | 38.0 | 2.9x | 239 | 213 |
| query dense (position+velocity) | 100 | 2787 | 515 | 5.4x | 3360 | 352 |
| query sparse (marker) | 100 | 1796 | 376 | 4.8x | 1360 | 192 |
| get | 100 | 8.2 | 2.8 | 2.9x | 0.0 | 0.0 |
| step steady | 100 | 6434 | 1426 | 4.5x | 4840 | 664 |
| step churn (size/10 edits) | 100 | 9159 | 3481 | 2.6x | 10017 | 1880 |
| snapshot | 100 | 12915 | 11015 | 1.2x | 40847 | 44514 |
| restore | 100 | 17790 | 5885 | 3.0x | 44458 | 23778 |
| spawn+attach | 1000 | 59.6 | 33.3 | 1.8x | 287 | 198 |
| query dense (position+velocity) | 1000 | 32480 | 4150 | 7.8x | 75946 | 2754 |
| query sparse (marker) | 1000 | 19665 | 2380 | 8.3x | 41490 | 1090 |
| get | 1000 | 9.0 | 3.0 | 3.0x | 13.9 | 0.0 |
| step steady | 1000 | 70805 | 10495 | 6.7x | 139585 | 3913 |
| step churn (size/10 edits) | 1000 | 98520 | 18870 | 5.2x | 218057 | 18662 |
| snapshot | 1000 | 89350 | 115750 | 0.8x | 419720 | 489200 |
| restore | 1000 | 190600 | 66250 | 2.9x | 476520 | 256408 |
| spawn+attach | 10000 | 95.9 | 35.1 | 2.7x | 296 | 223 |
| query dense (position+velocity) | 10000 | 472100 | 108000 | 4.4x | 826632 | 26776 |
| query sparse (marker) | 10000 | 319150 | 34550 | 9.2x | 439536 | 10112 |
| get | 10000 | 14.7 | 2.8 | 5.2x | 15.8 | 0.0 |
| step steady | 10000 | 874350 | 173200 | 5.0x | 1516284 | 36924 |
| step churn (size/10 edits) | 10000 | 1424500 | 317300 | 4.5x | 2356928 | 246272 |
| snapshot | 10000 | 1134600 | 1389400 | 0.8x | 4194648 | 4953224 |
| restore | 10000 | 978300 | 1161600 | 0.8x | 4363968 | 2585824 |

### Kotlin/JS on Node.js kotlin=2.4.21 samples=9

| case | entities | baseline ns/op | sparse-set ns/op | speedup | baseline B/op | sparse-set B/op |
|---|---:|---:|---:|---:|---:|---:|
| spawn+attach | 100 | 3353 | 1708 | 2.0x | n/a | n/a |
| query dense (position+velocity) | 100 | 17184 | 1064 | 16.2x | n/a | n/a |
| query sparse (marker) | 100 | 6686 | 624 | 10.7x | n/a | n/a |
| get | 100 | 98.1 | 46.8 | 2.1x | n/a | n/a |
| step steady | 100 | 57926 | 16222 | 3.6x | n/a | n/a |
| step churn (size/10 edits) | 100 | 56095 | 22260 | 2.5x | n/a | n/a |
| snapshot | 100 | 54815 | 25200 | 2.2x | n/a | n/a |
| restore | 100 | 52545 | 34575 | 1.5x | n/a | n/a |
| spawn+attach | 1000 | 2998 | 1958 | 1.5x | n/a | n/a |
| query dense (position+velocity) | 1000 | 226445 | 10845 | 20.9x | n/a | n/a |
| query sparse (marker) | 1000 | 102535 | 4840 | 21.2x | n/a | n/a |
| get | 1000 | 172 | 60.1 | 2.9x | n/a | n/a |
| step steady | 1000 | 588120 | 198475 | 3.0x | n/a | n/a |
| step churn (size/10 edits) | 1000 | 802610 | 299730 | 2.7x | n/a | n/a |
| snapshot | 1000 | 595250 | 277300 | 2.1x | n/a | n/a |
| restore | 1000 | 478700 | 288600 | 1.7x | n/a | n/a |
| spawn+attach | 10000 | 2269 | 2236 | 1.0x | n/a | n/a |
| query dense (position+velocity) | 10000 | 1537650 | 124400 | 12.4x | n/a | n/a |
| query sparse (marker) | 10000 | 798200 | 107150 | 7.4x | n/a | n/a |
| get | 10000 | 109 | 57.1 | 1.9x | n/a | n/a |
| step steady | 10000 | 5339450 | 2765750 | 1.9x | n/a | n/a |
| step churn (size/10 edits) | 10000 | 6723500 | 5918300 | 1.1x | n/a | n/a |
| snapshot | 10000 | 4674900 | 4469500 | 1.0x | n/a | n/a |
| restore | 10000 | 4966000 | 3937300 | 1.3x | n/a | n/a |

### Kotlin/Wasm on Node.js kotlin=2.4.21 samples=9

| case | entities | baseline ns/op | sparse-set ns/op | speedup | baseline B/op | sparse-set B/op |
|---|---:|---:|---:|---:|---:|---:|
| spawn+attach | 100 | 164 | 117 | 1.4x | n/a | n/a |
| query dense (position+velocity) | 100 | 4948 | 748 | 6.6x | n/a | n/a |
| query sparse (marker) | 100 | 2577 | 368 | 7.0x | n/a | n/a |
| get | 100 | 25.4 | 12.3 | 2.1x | n/a | n/a |
| step steady | 100 | 12761 | 3641 | 3.5x | n/a | n/a |
| step churn (size/10 edits) | 100 | 16028 | 6391 | 2.5x | n/a | n/a |
| snapshot | 100 | 46230 | 18085 | 2.6x | n/a | n/a |
| restore | 100 | 23345 | 14750 | 1.6x | n/a | n/a |
| spawn+attach | 1000 | 141 | 104 | 1.4x | n/a | n/a |
| query dense (position+velocity) | 1000 | 59230 | 7515 | 7.9x | n/a | n/a |
| query sparse (marker) | 1000 | 29850 | 3375 | 8.8x | n/a | n/a |
| get | 1000 | 36.8 | 12.3 | 3.0x | n/a | n/a |
| step steady | 1000 | 142465 | 35395 | 4.0x | n/a | n/a |
| step churn (size/10 edits) | 1000 | 192470 | 67350 | 2.9x | n/a | n/a |
| snapshot | 1000 | 513800 | 187450 | 2.7x | n/a | n/a |
| restore | 1000 | 244550 | 162500 | 1.5x | n/a | n/a |
| spawn+attach | 10000 | 185 | 113 | 1.6x | n/a | n/a |
| query dense (position+velocity) | 10000 | 891500 | 107200 | 8.3x | n/a | n/a |
| query sparse (marker) | 10000 | 371500 | 44100 | 8.4x | n/a | n/a |
| get | 10000 | 38.2 | 13.8 | 2.8x | n/a | n/a |
| step steady | 10000 | 1757400 | 471100 | 3.7x | n/a | n/a |
| step churn (size/10 edits) | 10000 | 2451800 | 912200 | 2.7x | n/a | n/a |
| snapshot | 10000 | 8551900 | 2705400 | 3.2x | n/a | n/a |
| restore | 10000 | 3675700 | 5383200 | 0.7x | n/a | n/a |

### native (WINDOWS X64, debug=false) kotlin=2.4.21 samples=9

| case | entities | baseline ns/op | sparse-set ns/op | speedup | baseline B/op | sparse-set B/op |
|---|---:|---:|---:|---:|---:|---:|
| spawn+attach | 100 | 467 | 286 | 1.6x | n/a | n/a |
| query dense (position+velocity) | 100 | 15928 | 3532 | 4.5x | n/a | n/a |
| query sparse (marker) | 100 | 7135 | 1362 | 5.2x | n/a | n/a |
| get | 100 | 106 | 65.9 | 1.6x | n/a | n/a |
| step steady | 100 | 37900 | 15094 | 2.5x | n/a | n/a |
| step churn (size/10 edits) | 100 | 48449 | 20662 | 2.3x | n/a | n/a |
| snapshot | 100 | 89505 | 54860 | 1.6x | n/a | n/a |
| restore | 100 | 73785 | 50445 | 1.5x | n/a | n/a |
| spawn+attach | 1000 | 469 | 280 | 1.7x | n/a | n/a |
| query dense (position+velocity) | 1000 | 165155 | 33620 | 4.9x | n/a | n/a |
| query sparse (marker) | 1000 | 77420 | 13265 | 5.8x | n/a | n/a |
| get | 1000 | 108 | 67.1 | 1.6x | n/a | n/a |
| step steady | 1000 | 415400 | 146950 | 2.8x | n/a | n/a |
| step churn (size/10 edits) | 1000 | 573980 | 233360 | 2.5x | n/a | n/a |
| snapshot | 1000 | 994800 | 539200 | 1.8x | n/a | n/a |
| restore | 1000 | 740950 | 513450 | 1.4x | n/a | n/a |
| spawn+attach | 10000 | 552 | 297 | 1.9x | n/a | n/a |
| query dense (position+velocity) | 10000 | 1911950 | 340700 | 5.6x | n/a | n/a |
| query sparse (marker) | 10000 | 948100 | 133300 | 7.1x | n/a | n/a |
| get | 10000 | 128 | 67.2 | 1.9x | n/a | n/a |
| step steady | 10000 | 4710250 | 1469800 | 3.2x | n/a | n/a |
| step churn (size/10 edits) | 10000 | 6365500 | 2431500 | 2.6x | n/a | n/a |
| snapshot | 10000 | 13508300 | 7941500 | 1.7x | n/a | n/a |
| restore | 10000 | 8622600 | 5988000 | 1.4x | n/a | n/a |

