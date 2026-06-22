package pl.net.brach;

import java.awt.print.PrinterJob;
import java.io.*;
import java.net.*;
import java.text.DecimalFormat;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.net.brach.commons.data.CurrencyRepository;
import pl.net.brach.commons.data.VatRepository;
import pl.net.brach.commons.nbp.NbpClient;
import pl.net.brach.commons.nbp.NbpRate;
import pl.net.brach.commons.ui.Dialogs;
import pl.net.brach.commons.ui.R4TechBannerView;
import javax.print.*;
import javax.print.attribute.*;
import javax.print.attribute.standard.*;

public class MainWindowController implements Initializable {

    private static final List<String> DATA_FORMATS = Arrays.asList("dd-MM-yyyy", "dd/MM/yyyy", "ddMMyyyy", "dd.MM.yyyy",
            "yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd", "yyyy.MM.dd");

    private static final String[] ACCOUNTING_TYPES = {"W walucie", "W PLN"};

    private static final Logger log = LoggerFactory.getLogger(MainWindowController.class);

    private final NbpClient nbpClient = new NbpClient();

    @FXML
    private Button bClose;
    @FXML
    private RadioButton rbPrint;
    @FXML
    private RadioButton rbVAT;
    @FXML
    private TextField tbTransactionAmount;
    @FXML
    private DatePicker dpTransactionDate;
    @FXML
    private ComboBox<String> cbCurrencies;
    @FXML
    private ComboBox<String> cbVAT;
    @FXML
    private ComboBox<String> cbAccountingType;
    @FXML
    private StackPane bannerContainer;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        bannerContainer.getChildren().add(new R4TechBannerView());

        addCurrenciesToComboBox();
        addVATRatesToComboBox();
        addAccountingTypesToComboBox();

        dpTransactionDate.getEditor().addEventFilter(KeyEvent.KEY_PRESSED, (KeyEvent keyEvent) -> {
            if (keyEvent.getCode() == KeyCode.DOWN) {
                dpTransactionDate.setValue(dpTransactionDate.getValue().minusDays(1));
                keyEvent.consume();
            }
            if (keyEvent.getCode() == KeyCode.UP) {
                dpTransactionDate.setValue(dpTransactionDate.getValue().plusDays(1));
                keyEvent.consume();
            }
        });

        tbTransactionAmount.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*,")) {
                tbTransactionAmount.setText(newValue.replaceAll("[^\\d,]", ""));
            }
        });

        dpTransactionDate.getEditor().textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue.length() > 10) {
                // Cap at a full date (dd-MM-yyyy) instead of wiping the whole field.
                dpTransactionDate.getEditor().setText(newValue.substring(0, 10));
            }
        });

        modifyDatePickers();
    }

    private void modifyDatePickers() {
        dpTransactionDate.setConverter(new StringConverter<LocalDate>() {
            @Override
            public String toString(LocalDate date) {
                if (date != null) {
                    for (String pattern : DATA_FORMATS) {
                        try {
                            if (date.isAfter(LocalDate.now())) {
                                return DateTimeFormatter.ofPattern(pattern).format(LocalDate.now());
                            } else {
                                return DateTimeFormatter.ofPattern(pattern).format(date);
                            }
                        } catch (DateTimeException dte) {
                            System.out.println("Format Error");
                        }
                    }
                }
                return "";
            }

            @Override
            public LocalDate fromString(String string) {
                if (string != null && !string.isEmpty()) {
                    for (String pattern : DATA_FORMATS) {
                        try {
                            return LocalDate.parse(string, DateTimeFormatter.ofPattern(pattern));
                        } catch (DateTimeParseException ignored) {
                        }
                    }
                    System.out.println("Parse Error");
                }
                return null;
            }
        });
    }

    private void addCurrenciesToComboBox() {
        CurrencyRepository currencyRepository = new CurrencyRepository();

        cbCurrencies.getItems().clear();
        cbCurrencies.getItems().addAll(currencyRepository.getCurrencies());
        cbCurrencies.getSelectionModel().selectFirst();
    }

    private void addVATRatesToComboBox() {
        VatRepository vatRepository = new VatRepository();

        cbVAT.getItems().clear();
        cbVAT.getItems().addAll(vatRepository.getVatRates());
        cbVAT.getSelectionModel().selectFirst();
    }

    private void addAccountingTypesToComboBox() {
        cbAccountingType.getItems().clear();
        cbAccountingType.getItems().addAll(ACCOUNTING_TYPES);
        cbAccountingType.getSelectionModel().selectFirst();
    }

    @FXML
    private void currencyChosen() {
        cbCurrencies.getSelectionModel().select(cbCurrencies.getSelectionModel().getSelectedItem()); }

    @FXML
    private void vatRateChosen() {
        cbVAT.getSelectionModel().select(cbVAT.getSelectionModel().getSelectedItem());
    }

    @FXML
    private void accountingTypeChosen() {
        cbAccountingType.getSelectionModel().select(cbAccountingType.getSelectionModel().getSelectedItem());}

    @FXML
    private void okClicked() {
        //Get user input data
        if (dpTransactionDate.getEditor().getText().isEmpty()) {
            System.out.println("No data was provided. Aborting...");
            return;
        }

        // Capture inputs on the FX thread; only the network call runs in the background.
        final LocalDate dTransactionDate = extractTransactionDate();
        final String currency = cbCurrencies.getValue();

        Task<NbpRate> fetchRate = new Task<>() {
            @Override
            protected NbpRate call() throws Exception {
                return nbpClient.fetchRatePrecedingDate(currency, dTransactionDate);
            }
        };

        fetchRate.setOnSucceeded(event -> showResult(fetchRate.getValue()));
        fetchRate.setOnFailed(event -> {
            log.error("Nie udało się pobrać kursu z NBP", fetchRate.getException());
            Dialogs.error("Nie udało się pobrać kursu z NBP",
                    "Sprawdź połączenie z internetem i spróbuj ponownie.");
        });

        Thread worker = new Thread(fetchRate, "nbp-fetch");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs on the FX thread (Task onSucceeded): builds the summary params, optionally prints, and shows the summary. */
    private void showResult(NbpRate rate) {
        String rateText = rate.midPlain();
        double calculatedTransactionNetValue = calculateNetAmount(rateText);

        String[] params;
        DecimalFormat format = new DecimalFormat("###,##0.00");

        if (rbVAT.isSelected()) {
            double calculatedTransactionVatValue = calculateVatAmount(rateText);
            params = new String[6];
            params[5] = format.format(calculatedTransactionVatValue) + " zł";
        } else {
            params = new String[5];
        }

        params[0] = format.format(Double.parseDouble(tbTransactionAmount.getText().replace(",", ".")))
                + " " + cbCurrencies.getValue();
        params[1] = rate.tableNumber();
        params[2] = rate.effectiveDate().toString();
        params[3] = rateText.replace(".", ",");
        params[4] = format.format(calculatedTransactionNetValue) + " zł";

        if (rbPrint.isSelected()) {
            ArrayList<String> labelText = generateLabel(params);
            printLabel(labelText);
        }

        try {
            ExchangeRates.displaySummary(params);
        } catch (IOException e) {
            log.error("Nie udało się otworzyć podsumowania", e);
            Dialogs.error("Błąd", "Nie udało się otworzyć okna podsumowania.");
        }
    }

    private LocalDate extractTransactionDate() {
        LocalDate dTransactionDate = null;
        for (String pattern : DATA_FORMATS) {
            try {
                dTransactionDate = LocalDate.parse(dpTransactionDate.getEditor().getText(), DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) { }
        }
        return dTransactionDate;
    }

    private double calculateNetAmount(String sRate) {
        return Math.round((Double.parseDouble(tbTransactionAmount.getText().replace(",", "."))
                * Double.parseDouble(sRate)) * 100.00) / 100.00;
    }

    private double calculateVatAmount(String sRate) {
        if (cbAccountingType.getSelectionModel().isSelected(0)) {
            return Math.round(
                    Math.round(((Double.parseDouble(tbTransactionAmount.getText().replace(",", "."))
                            * Double.parseDouble(cbVAT.getValue().replace("%", "")) / 100) * 100.00)) / 100.00
                            * Double.parseDouble(sRate) * 100.00) / 100.00;
        } else {
            return Math.round(Double.parseDouble(tbTransactionAmount.getText().replace(",", "."))
                    * Double.parseDouble(cbVAT.getValue().replace("%", "")) / 100
                    * Double.parseDouble(sRate) * 100.00) / 100.00;
        }
    }

    private ArrayList<String> generateLabel(String[] args) {
        ArrayList<String> stringArrayList = new ArrayList<>();

        stringArrayList.add(" --------------------------");
        stringArrayList.add("   Kurs 1 " + cbCurrencies.getValue() + " = " + args[3]);
        stringArrayList.add("  wg tab.: " + args[1]);
        stringArrayList.add("     z dn. " + args[2]);
        stringArrayList.add(" --------------------------");
        stringArrayList.add(" " + args[0] + " * " + args[3]);
        stringArrayList.add(" = " + args[4]);

        if (rbVAT.isSelected()) {
            stringArrayList.add(" --------------------------");
            stringArrayList.add(" VAT " + cbVAT.getValue() + " = " + args[5]);
            stringArrayList.add(" --------------------------");
        } else {
            stringArrayList.add(" --------------------------");
            stringArrayList.add(" ");
            stringArrayList.add(" ");
        }

        return stringArrayList;
    }

    private static PrintService getPrintService(String printerName) {
        PrintService printService = null;
        PrintService[] printServices = PrinterJob.lookupPrintServices();

        for (PrintService service : printServices) {
            if (service.getName().equals(printerName)) {
                printService = service;
            }
        }
        return printService;
    }

    private void printLabel(ArrayList<String> stringArrayListToPrint) {
        PrintRequestAttributeSet pras = new HashPrintRequestAttributeSet();
        pras.add(OrientationRequested.PORTRAIT);
        pras.add(new MediaPrintableArea(0, 0, LabelPrint.PRINT_PAGE_HEIGHT, LabelPrint.PRINT_PAGE_WIDTH, MediaPrintableArea.MM));
        pras.add(new JobName(ExchangeRates.BRACHSOFT_TITLE + " - Dokument", null));

        // Prefer the primary label printer, fall back to the secondary; both may be absent.
        PrintService printService = getPrintService(ExchangeRates.PRIMARY_PRINTER_NAME);
        if (printService == null) {
            printService = getPrintService(ExchangeRates.SECONDARY_PRINTER_NAME);
        }
        if (printService == null) {
            log.warn("Nie znaleziono drukarki etykiet ({} ani {})",
                    ExchangeRates.PRIMARY_PRINTER_NAME, ExchangeRates.SECONDARY_PRINTER_NAME);
            Dialogs.error("Nie znaleziono drukarki",
                    "Nie znaleziono drukarki etykiet: " + ExchangeRates.PRIMARY_PRINTER_NAME
                            + " ani " + ExchangeRates.SECONDARY_PRINTER_NAME + ".");
            return;
        }

        try {
            PrinterJob printerJob = PrinterJob.getPrinterJob();
            printerJob.setPrintService(printService);
            printerJob.setPrintable(new LabelPrint(stringArrayListToPrint));
            printerJob.print(pras);
            log.info("Etykieta wysłana do drukarki: {}", printService.getName());
        } catch (Exception ex) {
            log.error("Nie udało się wydrukować etykiety na: {}", printService.getName(), ex);
            Dialogs.error("Błąd drukowania",
                    "Nie udało się wydrukować etykiety na drukarce: " + printService.getName() + ".");
        }
    }

    @FXML
    private void closeClicked() {
        Stage stage = (Stage) bClose.getScene().getWindow();
        stage.close();
    }
}
