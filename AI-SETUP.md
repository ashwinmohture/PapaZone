# PapaZone AI Features

Implemented features:
- AI Food Ordering Assistant
- Personalized Food Recommendations
- AI Admin Assistant

The AI backend is `backend/ai-service`, running on port 8088 behind the API Gateway route `/api/ai/**`.

## 1. Environment Configuration

Configure these variables in your local `.env` file:

| Variable | Purpose |
|---|---|
| `JWT_SECRET` | Shared JWT signing secret used by customer-service and verified by ai-service |
| `INTERNAL_API_KEY` | Shared internal API key used by order-service and ai-service |
| `GEMINI_API_KEY` | Google Gemini API key for AI chat and admin assistant |
| `AI_MODEL` | Gemini model; default is `gemini-2.5-flash` |
| `GEMINI_BASE_URL` | Gemini API base URL; default is `https://generativelanguage.googleapis.com` |

Never commit `.env` or expose API keys and passwords in source control.

## 2. Run the Application

From the project root:

```bash
docker compose up -d --build
```

Local application URLs:

- Frontend: http://localhost:5173
- API Gateway: http://localhost:8080
- AI Service: http://localhost:8088

## 3. Admin Assistant Access

The AI Admin Assistant requires an authenticated JWT containing the `ADMIN` role. Normal registration creates a `CUSTOMER` account.

To promote an existing account, execute the following SQL using your configured MySQL credentials:

```sql
UPDATE papazone_customer.customers
SET role = 'ADMIN'
WHERE email = 'you@example.com';
```

Log out and sign in again to receive a token containing the updated role.

## 4. Run Tests

```bash
cd backend
mvn -pl ai-service -am test
```

## 5. Try the AI APIs

Popular recommendations:

```bash
curl "http://localhost:8080/api/ai/recommendations?limit=6"
```

Personalized recommendations:

```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/ai/recommendations"
```

AI food ordering assistant:

```bash
curl -X POST http://localhost:8080/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Suggest something under 300"}]}'
```

AI admin assistant:

```bash
curl -X POST http://localhost:8080/api/ai/admin/ask \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{"question":"How many orders did we receive today?"}'
```

## 6. Design and Safety

- Chat responses use structured tool calls. Menu item IDs, names and prices are validated against live menu data.
- The AI cannot directly add items to a customer's cart.
- Recommendations are deterministic and use order history and popular menu items.
- Admin queries use fixed metric and period options.
- Customer identity and role are obtained from a verified JWT.
- Rate limiting, message-length limits and conversation-history limits are implemented.
- The internal order export endpoint requires `INTERNAL_API_KEY` and excludes addresses and payment data.

## 7. Known Limitations

1. Cart, orders, `/admin` and `GET /api/orders` are not yet protected by JWT. Cart requests still use the client-supplied customer ID.
2. The menu does not currently have dedicated spice-level or dietary fields.
3. The full menu is included in the AI prompt.
4. Revenue is calculated from non-cancelled order totals, including tax and delivery.
5. Run the Maven tests in an environment with Maven Central access to verify the complete Spring application.
