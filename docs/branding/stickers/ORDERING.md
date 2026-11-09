# Ordering the stickers at Flyeralarm

How to order the print files in this directory. [BRANDING.md](../../BRANDING.md) § Stickers describes the designs. `scripts/build-stickers.sh` builds them.

## Before the first order

Flyeralarm draws the 85 × 55 mm and 105 × 35 mm datasheets in portrait. The print files here are landscape. Ask Flyeralarm support if they accept
landscape files, or if you must turn them by 90°. Send this text through the contact form or by e-mail:

> Hallo, ich möchte Outdoor-Aufkleber in 85 × 55 mm und 105 × 35 mm bestellen. Die Datenblätter zeigen beide Formate im Hochformat, meine Druckdaten sind im
> Querformat angelegt. Kann ich die Dateien im Querformat hochladen, oder muss ich sie um 90° drehen?

Record the answer on #481.

## Which file goes to which product

Flyeralarm sells fixed formats only. Each file fits exactly one product and one format.

| Files                                                       | Product                   | Options                                      |
| ----------------------------------------------------------- | ------------------------- | -------------------------------------------- |
| `sticker-{en,de}-{ink,night}-85x55-cmyk.pdf`                | Outdoor-Aufkleber         | Rectangle 85 × 55 mm, white film, 4/0        |
| `sticker-mini-{ink,night}-50x50-cmyk.pdf`                   | Outdoor-Aufkleber         | Square 50 × 50 mm, white film, 4/0           |
| `sticker-{weekcard,leaflet}[-plain]-{de,en}-85x55-cmyk.pdf` | Outdoor-Aufkleber         | Rectangle 85 × 55 mm, white film, 4/0        |
| `sticker-72h-en-85x55-cmyk.pdf`                             | Outdoor-Aufkleber         | Rectangle 85 × 55 mm, white film, 4/0        |
| `sticker-night-{2000,0200,0800}-{de,en}-50x50-cmyk.pdf`     | Outdoor-Aufkleber         | Square 50 × 50 mm, white film, 4/0           |
| `sticker-clear-{en,de}-{1..4}-105x35-cmyk.pdf`              | Outdoor-Aufkleber         | Rectangle 105 × 35 mm, 90 µm clear film, 4/0 |
| `sticker-neon-{en,de}-85x55-cmyk.pdf`                       | Neon-Aufkleber            | 85 × 55 mm, red, 1/0 (black only)            |
| `sticker-glow-{en,de}-85x55-cmyk.pdf`                       | Leuchtaufkleber           | 85 × 55 mm, 4/0                              |
| `sticker-diecut-sheet-a3-cmyk.pdf`                          | Selbstklebefolie Freiform | DIN A3, one sheet                            |
| `sticker-pin-sheet[-plain]-{de,en}-a3-cmyk.pdf`             | Selbstklebefolie Freiform | DIN A3, one sheet per language               |

Upload the `-cmyk.pdf` file. The `-preview.png` file shows the sticker after the cut and is not a print file.

## Order a test run

Order a small test run first. Use two designs on white film, for example `sticker-en-ink-85x55-cmyk.pdf` and `sticker-de-ink-85x55-cmyk.pdf`.

1. Open [flyeralarm.de](https://www.flyeralarm.com/de/) and go to **Aufkleber**.
2. Select the product and the options from the table above.
3. Select the smallest quantity.
4. Add one cart line for each design.
5. Upload the PDF for each line.
6. Select the data check.
7. Pay.

Flyeralarm product pages need JavaScript. Read the options in a browser, not with `curl`.

## If the data check rejects a file

- **Transparency.** The frame wear of the stamp is live transparency. Flatten the file to PDF/X-4 and upload it again.
- **Format.** Compare the file against the datasheet of the product. The datasheet link shows after you select the options. A file in the wrong format
  needs a new design, not a new number.

## After the stickers arrive

1. Ask the venue before you put up a sticker. A sticker with permission stays on the wall.
2. Count the scans in the first 14 days. The frontend access log keeps the query string `?s=<code>` for 14 days only.
3. Write the counts per QR code on #481. BRANDING.md § Stickers lists the code of each design.
