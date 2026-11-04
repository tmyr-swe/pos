package com.chegg.pos.service;

import com.chegg.pos.app.AppPaths;
import com.chegg.pos.model.SaleItemRow;
import com.chegg.pos.model.SaleSummary;
import com.chegg.pos.model.ZReport;
import com.chegg.pos.util.Money;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.printing.PDFPageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import java.awt.Desktop;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Renders A4/A5 receipt and Z-report PDFs with the bundled font and logo. */
@Service
public class ReceiptService {

    private static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("dd MMM yyyy HH:mm:ss").withZone(EAT);

    private final JdbcTemplate jdbc;
    private final SaleService sales;
    private final SettingsService settings;

    public ReceiptService(JdbcTemplate jdbc, SaleService sales, SettingsService settings) {
        this.jdbc = jdbc;
        this.sales = sales;
        this.settings = settings;
    }

    public Path generateReceipt(long saleId) throws IOException {
        SaleSummary sale = sales.findById(saleId);
        if (sale == null) {
            throw new IllegalArgumentException("Sale not found.");
        }
        List<SaleItemRow> items = sales.items(saleId);
        List<PaymentRow> payments = payments(saleId);

        PDRectangle pageSize = pageSize();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(pageSize);
            document.addPage(page);

            PDType0Font regular = loadFont(document, "/fonts/JetBrainsMono-Regular.ttf");
            PDType0Font bold = loadFont(document, "/fonts/JetBrainsMono-Bold.ttf");
            float margin = 36f;
            float width = pageSize.getWidth() - margin * 2;
            float y = pageSize.getHeight() - margin;

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                y = drawHeader(document, content, bold, regular, margin, y, width);
                y -= 8;

                y = text(content, regular, 9f, margin, y, "Receipt  " + sale.receiptNumber());
                y = text(content, regular, 8f, margin, y, "Date     " + STAMP.format(sale.createdAt()));
                y = text(content, regular, 8f, margin, y, "Cashier  " + sale.cashierName());
                y = text(content, regular, 8f, margin, y, "Shift    #" + sale.shiftId()
                        + "   Channel " + sale.channel());
                y -= 4;
                y = rule(content, margin, y, width);

                for (SaleItemRow item : items) {
                    String name = clip(item.name(), 30);
                    y = text(content, regular, 8f, margin, y,
                            String.format("%-30s %3d x %10s", name, item.quantity(),
                                    Money.formatPlain(item.unitPriceCents())));
                    y = text(content, regular, 8f, margin + 10, y,
                            String.format("%-30s %21s", clip(item.sku(), 30),
                                    Money.formatPlain(item.lineTotalCents())));
                    if (y < margin + 150) {
                        break;
                    }
                }

                y -= 2;
                y = rule(content, margin, y, width);
                y = amountLine(content, regular, margin, y, width, "Subtotal", sale.subtotalCents());
                if (sale.discountCents() != 0) {
                    y = amountLine(content, regular, margin, y, width, "Discounts", -sale.discountCents());
                }
                if (sale.taxCents() != 0) {
                    y = amountLine(content, regular, margin, y, width, "Tax", sale.taxCents());
                }
                if (sale.roundingCents() != 0) {
                    y = amountLine(content, regular, margin, y, width, "Cash rounding", sale.roundingCents());
                }
                y = amountLine(content, bold, margin, y, width, "TOTAL", sale.totalCents());
                y -= 3;

                for (PaymentRow payment : payments) {
                    String label = payment.method().equals("CASH") ? "Cash" : "Card";
                    y = amountLine(content, regular, margin, y, width, label, payment.amountCents());
                    if (payment.method().equals("CASH") && payment.tenderedCents() != payment.amountCents()) {
                        y = amountLine(content, regular, margin, y, width, "Tendered", payment.tenderedCents());
                        y = amountLine(content, regular, margin, y, width, "Change", payment.changeCents());
                    }
                }

                y -= 4;
                y = rule(content, margin, y, width);
                y = text(content, regular, 7.5f, margin, y,
                        "No VAT charged. Thank you for shopping at " + settings.storeName() + ".");
                text(content, regular, 7f, margin, Math.max(margin, y),
                        "Powered by Chegg POS  ·  " + STAMP.format(java.time.Instant.now()));
            }

            Path path = receiptPath(sale);
            Files.createDirectories(path.getParent());
            document.save(path.toFile());
            return path;
        }
    }

    public Path generateZReport(ZReport report) throws IOException {
        PDRectangle pageSize = new PDRectangle(PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            PDType0Font regular = loadFont(document, "/fonts/JetBrainsMono-Regular.ttf");
            PDType0Font bold = loadFont(document, "/fonts/JetBrainsMono-Bold.ttf");
            float margin = 42f;
            float y = pageSize.getHeight() - margin;
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                try (InputStream logo = getClass().getResourceAsStream("/branding/logo.jpg")) {
                    if (logo != null) {
                        PDImageXObject image = PDImageXObject.createFromByteArray(
                                document, logo.readAllBytes(), "logo");
                        float height = 42;
                        float width = height * image.getWidth() / image.getHeight();
                        content.drawImage(image, margin, y - height, width, height);
                        y -= height + 16;
                    }
                }
                y = text(content, bold, 14f, margin, y, "Z REPORT - Shift #" + report.shiftId());
                y -= 4;
                for (String line : report.reportText().split("\n")) {
                    if (y < margin) {
                        break;
                    }
                    y = text(content, regular, 8.5f, margin, y, line);
                }
            }
            Path dir = AppPaths.receipts()
                    .resolve(java.time.LocalDate.now(EAT).format(DateTimeFormatter.ofPattern("yyyy/MM")));
            Files.createDirectories(dir);
            Path path = dir.resolve("Z-shift-" + report.shiftId() + ".pdf");
            document.save(path.toFile());
            return path;
        }
    }

    public String receiptText(long saleId) {
        SaleSummary sale = sales.findById(saleId);
        if (sale == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append(settings.storeName()).append('\n');
        text.append("Receipt ").append(sale.receiptNumber()).append('\n');
        text.append(STAMP.format(sale.createdAt())).append('\n');
        text.append("Cashier: ").append(sale.cashierName()).append('\n');
        text.append("-".repeat(36)).append('\n');
        for (SaleItemRow item : sales.items(saleId)) {
            text.append(item.quantity()).append(" x ").append(item.name())
                    .append("  ").append(Money.format(item.lineTotalCents())).append('\n');
        }
        text.append("-".repeat(36)).append('\n');
        text.append("TOTAL ").append(Money.format(sale.totalCents())).append('\n');
        text.append("No VAT charged.\n");
        return text.toString();
    }

    public void print(Path pdf) throws Exception {
        String pageSize = settings.get("receipt.pageSize", "A5");
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            if (services.length > 0) {
                PrinterJob job = PrinterJob.getPrinterJob();
                job.setPageable(new PDFPageable(document));
                if (job.printDialog()) {
                    job.print();
                    return;
                }
            }
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.PRINT)) {
            Desktop.getDesktop().print(pdf.toFile());
        } else {
            throw new IllegalStateException("No printer is available.");
        }
    }

    private float drawHeader(PDDocument document, PDPageContentStream content,
                             PDType0Font bold, PDType0Font regular,
                             float margin, float y, float width) throws IOException {
        try (InputStream logo = getClass().getResourceAsStream("/branding/logo.jpg")) {
            if (logo != null) {
                PDImageXObject image = PDImageXObject.createFromByteArray(
                        document, logo.readAllBytes(), "logo");
                float height = 46;
                float imageWidth = height * image.getWidth() / image.getHeight();
                content.drawImage(image, margin, y - height, imageWidth, height);
                y -= height + 8;
            }
        }
        y = text(content, bold, 14f, margin, y, settings.storeName());
        String address = settings.get("store.address", "");
        String phone = settings.get("store.phone", "");
        String pin = settings.get("store.pin", "");
        if (!address.isBlank()) {
            y = text(content, regular, 8f, margin, y, address);
        }
        if (!phone.isBlank()) {
            y = text(content, regular, 8f, margin, y, "Tel " + phone);
        }
        if (!pin.isBlank()) {
            y = text(content, regular, 8f, margin, y, "PIN " + pin);
        }
        return y;
    }

    private float amountLine(PDPageContentStream content, PDType0Font font,
                             float margin, float y, float width,
                             String label, long cents) throws IOException {
        float size = 9f;
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(margin, y);
        content.showText(label);
        content.endText();

        String amount = Money.formatPlain(cents);
        float textWidth = font.getStringWidth(amount) / 1000f * size;
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(margin + width - textWidth, y);
        content.showText(amount);
        content.endText();
        return y - 13;
    }

    private float text(PDPageContentStream content, PDType0Font font, float size,
                       float x, float y, String value) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(sanitize(value));
        content.endText();
        return y - (size + 3);
    }

    private float rule(PDPageContentStream content, float x, float y, float width) throws IOException {
        content.setLineWidth(0.5f);
        content.moveTo(x, y);
        content.lineTo(x + width, y);
        content.stroke();
        return y - 10;
    }

    private List<PaymentRow> payments(long saleId) {
        return jdbc.query("""
                SELECT method, amount_cents, tendered_cents, change_cents
                FROM payments WHERE sale_id = ? ORDER BY id
                """, (rs, rowNum) -> new PaymentRow(
                rs.getString("method"), rs.getLong("amount_cents"),
                rs.getLong("tendered_cents"), rs.getLong("change_cents")), saleId);
    }

    private PDRectangle pageSize() {
        return "A4".equalsIgnoreCase(settings.get("receipt.pageSize", "A5"))
                ? PDRectangle.A4 : new PDRectangle(PDRectangle.A5.getWidth(), PDRectangle.A5.getHeight());
    }

    private Path receiptPath(SaleSummary sale) {
        java.time.LocalDate date = sale.createdAt().atZone(EAT).toLocalDate();
        return AppPaths.receipts()
                .resolve(date.format(DateTimeFormatter.ofPattern("yyyy")))
                .resolve(date.format(DateTimeFormatter.ofPattern("MM")))
                .resolve(sale.receiptNumber() + ".pdf");
    }

    private static PDType0Font loadFont(PDDocument document, String resource) throws IOException {
        InputStream in = ReceiptService.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("Missing font " + resource);
        }
        try (in) {
            return PDType0Font.load(document, in, true);
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00A0', ' ').replace('…', '.');
    }

    private record PaymentRow(String method, long amountCents, long tenderedCents, long changeCents) {
    }
}
