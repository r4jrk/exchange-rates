# exchange-rates (Kursy walut)

Converts a foreign-currency amount to PLN at the NBP table-A rate for the day preceding the
transaction date, with optional VAT. Part of the [r4_tech tools](../README.md) suite.

## Run

```bash
mvn -DskipTests install            # once (from the repo root)
mvn -pl exchange-rates javafx:run
```

## Package

```bash
mvn -pl exchange-rates -am -Pinstaller -DskipTests package
# -> target/installer/r4_tech Kursy walut/
```

## Data files

Needs `waluty.csv` (currency codes) and `vat.csv` (VAT rates). A copy of each ships at the module
root. For a packaged app, put them next to the executable or set `-Dr4tech.dataDir=<dir>`.
See the [root README](../README.md#data-files-csv) for the full lookup order.
