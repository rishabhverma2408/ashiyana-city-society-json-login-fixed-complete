# Ashiyana City I Society Management - JSON Edition

Spring Boot application using JSON files instead of a database.

## Run

1. Open the project in IntelliJ IDEA.
2. Use Java 17+.
3. Run `SocietyManagementApplication.java`.
4. Open http://localhost:8080

## Default staff login

- Admin username: `7007478334`
- Secretary username: `8796854510`
- Default password for both: `123456`

Change passwords after first login if required.

## Storage

Data is stored in the project's `data` directory:

- `users.json`
- `members.json`
- `payments.json`
- `expenses.json`

No MySQL/database is required.

## Main features

- Admin and Secretary member creation
- Required flat number, member name, phone, monthly contribution and password
- Password length 6-15 characters
- BCrypt password storage
- Admin full member access
- Secretary name/email/contribution editing
- Payment status by month
- Payment history selection
- Expense management
- WhatsApp/email payment reminders
- 7-day HTTP session timeout
- Society members and expenses detail pages
