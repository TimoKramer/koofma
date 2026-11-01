# koofma

A local-first, shared shopping list. ClojureScript PWA (shadow-cljs,
Replicant, Tailwind/daisyUI) in the style of
[ivylee](https://github.com/timokramer/ivylee). Works fully offline; two users
sync through an S3/Cloudflare R2 bucket using a CRDT (per-field LWW registers
with hybrid logical clocks), so concurrent offline edits always merge.

- Multiple lists with custom names: switch with the tabs, **+** adds one,
  ✎ renames or deletes the current one (with **Undo**). Lists sync too; which
  list is open is remembered per device.
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

### REPL with Conjure (Neovim)

`npm run dev` also starts shadow-cljs's nREPL server on a random port and
writes it to `.shadow-cljs/nrepl.port`, which
[Conjure](https://github.com/Olical/conjure) picks up on its own.

1. Start the dev build: `npm run dev`.
2. Open http://localhost:8080 in a browser — ClojureScript evaluates in that
   page, so the REPL only works while a tab with the app is open.
3. Open any `.cljs`/`.cljc` file in Neovim. Conjure connects to the nREPL
   server (`:ConjureConnect` if it didn't).
4. Switch the session from Clojure to the app build: `:ConjureShadowSelect app`.
5. Evaluate as usual, e.g. `<localleader>ee` on a form. The `comment` blocks
   are good starting points:

   ```clojure
   (-> @koofma.main/!system :app-db deref)               ; current state
   (koofma.app/dispatch! @koofma.main/!system [:item/add {:title "foobar"}])
   ```

Restarting `npm run dev` changes the port. Run `:ConjureConnect` and
`:ConjureShadowSelect app` again.

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
