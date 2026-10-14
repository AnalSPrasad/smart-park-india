# Smart Parking Management System — Debugged VS Code Edition

A dependency-free Java web application for a college OOP project. It uses Java's built-in HTTP server and a browser-based UI. **No MySQL, Maven, Spring Boot, or extra Java libraries are needed.**

## Requirements
- JDK 17 or later
- VS Code (recommended, but optional)

## Run on Windows
1. Extract this ZIP file.
2. Open the extracted `smart-parking-debugged` folder in VS Code.
3. In the VS Code terminal, check Java is installed:
   ```powershell
   java -version
   ```
   Use JDK 17 or later.
4. Double-click `run.bat`. Alternatively, open the terminal in this folder and run:
   ```powershell
   java ParkingServer.java 8081
   ```
5. Open **http://localhost:8081** in your browser.
6. To stop the server, return to the terminal and press `Ctrl+C`.

### If you still get “Address already in use”
Another program is using the chosen port. Try:
```powershell
java ParkingServer.java 9090
```
Then open **http://localhost:9090**.

## Included features
- Live parking layout with 40 bays across two floors
- Car, EV, accessible and motorcycle bay types
- Check-in and check-out
- Automatic or manually selected bay assignment
- Time-limited reservations and cancellation
- Occupancy dashboard, recent receipts and revenue tracking
- Fee calculation with a free grace period, busy-hour multiplier and daily cap
- Online payment demonstration screen on checkout, with payment support contact 8590326534
- Updated professional Manrope and DM Sans typography

## Data storage
This demo keeps data in memory. Parking activity resets when the Java server is stopped or restarted.

## OOP concepts for your presentation
- **Encapsulation:** parking bay and garage state are represented by Java classes.
- **Abstraction:** garage operations expose check-in, check-out and reservation actions.
- **Enums:** vehicle and bay categories are defined with `enum` types.
- **Records:** checkout receipts use a Java record.
- **Collections:** lists store parking bays and receipt history.

The online payment button is a **simulation for college presentation only**; it does not transfer money. A real payment flow requires a verified UPI ID or payment gateway account. The support number 8590326534 is linked to WhatsApp. Check-in and reservation forms offer offline-at-counter or online-payment assistance choices; online payment is not processed by this demo.

This is a college demonstration project, not a production access-control or payment system.


## Important: start it the right way
Use `run.bat` or the VS Code terminal command above. Do not use the Code Runner “Run Code” button for this server; some Code Runner setups use a different working folder/classpath, which can cause the webpage and Java API to get out of sync.

The interface now displays all fees and revenue in Indian rupees (₹), and API connection errors show a more useful message.


## Visual refresh
The interface now uses a modern navy-and-teal palette, Manrope headings, DM Sans body text, and a locally stored vehicle banner image. The banner works without an external image service. Google Fonts are loaded online when an internet connection is available; system fallbacks are used otherwise.


## Admin page
Start the server with `run.bat`, then open `http://localhost:8081/admin.html` to view the separate admin dashboard. Use the **Admin dashboard** link in the main page to navigate there. The dashboard reads the same live in-memory API data as the parking page.


UPDATED FEATURES
- Admin dashboard password-only login: 1234 (demo only).
- Online payment assistance displays the supplied QR image at public/payment-qr.jpeg.
- The website does not verify payment automatically; confirm payee and amount in the UPI app.
