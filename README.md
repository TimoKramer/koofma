# koofma

A local-first, shared shopping list. ClojureScript PWA (shadow-cljs,
Replicant, Tailwind/daisyUI) in the style of
[ivylee](https://github.com/timokramer/ivylee). Works fully offline; two users
sync through an S3/Cloudflare R2 bucket using a CRDT (per-field LWW registers
with hybrid logical clocks), so concurrent offline edits always merge.

- Checking an item off deletes it; an **Undo** toast stays for 5 seconds.
- Reorder items by dragging the ⠿ handle (touch and mouse).
- The input suggests titles from everything ever entered (checked-off items
  included, synced from the partner too).

## Dev setup

```sh
npm install
npm run dev     # shadow-cljs watch + Tailwind watcher, http://localhost:8080
clojure -X:test
```

## Sync setup (S3 / R2)

Uses [konserve-s3](https://github.com/replikativ/konserve-s3)'s ClojureScript
backend (aws4fetch, ETag `If-Match` compare-and-set).

1. Create a bucket (it must already exist) and one API token per user, scoped
   to that bucket with object read & write.
2. Add a CORS policy — `ExposeHeaders: ETag` is essential:

   ```json
   [{"AllowedOrigins": ["https://<your-app-origin>", "http://localhost:8080"],
     "AllowedMethods": ["GET", "PUT", "DELETE", "HEAD"],
     "AllowedHeaders": ["authorization", "content-type", "if-match",
                        "if-none-match", "x-amz-*"],
     "ExposeHeaders": ["ETag"]}]
   ```
3. In the app: ⚙ → enter endpoint (`https://<account>.r2.cloudflarestorage.com`),
   bucket, the user's key/secret and **the same store id on both devices**.
