---
name: android-files
description: Files on the phone. Upload and download over HTTP, manage them with the file_* tools, and open an uploaded file in another app through send_intent.
---

# Files — upload, manage, open in an app

The phone exposes a small file system of named folders. File content
moves over two HTTP routes beside `/mcp`; everything else is the `file_*`
tool family. Every file has a `content://` URI, and `send_intent` with
that URI as its data opens the file in another app.

## Folders

A path is a folder name, then the path inside it: `tmp/report.pdf`.

- `tmp` is always there and always writable. It is private to the server
  app, and a file in it is deleted a day after it was written. Use it for
  a file another app only needs to read once.
- Every other folder is one the user added in the server app, under a
  name they chose, each read-write or read-only. These are real folders
  in the phone's storage, so other apps that scan or are pointed at them
  see the files too.

`file_list` with no path lists the folders and their access.

## Content: PUT and GET

Same host and port as `/mcp`, same `Authorization` header:

```
PUT /files/<path>     body: the file's raw bytes
GET /files/<path>     response: the file's raw bytes
```

```
curl -T report.pdf -H "Authorization: Bearer <token>" \
  http://<host>:<port>/files/tmp/report.pdf
```

A PUT makes any missing folders in the path and replaces a file already
there. One request carries the whole file whatever its size. It answers
with the file's entry as JSON:

```
{"path": "tmp/report.pdf", "type": "file", "size": 48213,
 "modified": "<time>", "uri": "content://<...>"}
```

Two things differ from an ordinary web server:

- A PUT must carry `Content-Length`. Uploading a file from disk sends it;
  a body streamed from a pipe is chunked instead and is refused with 400.
- Percent-encode the path as usual, and write a `+` as `%2B`: a bare `+`
  arrives as a space.

A refusal is a status code and `{"error": "<sentence>"}`: 400 for a
malformed path or request, 403 for a read-only folder or the file tools
switched off, 404 for nothing at the path, 409 when a folder or file is
in the way.

## Tools

| Tool | Does |
|---|---|
| `file_list` | a folder's entries, optionally recursive; the folders themselves for an empty path |
| `file_info` | one entry |
| `file_move` | rename or move, within a folder or between two |
| `file_delete` | delete a file or folder |
| `file_make_directory` | create a folder and any above it |

Their descriptions in `tools/list` carry the parameters. There is no
copy, and no tool reads or writes content. The named folders themselves
cannot be moved or deleted, only what is in them.

## Opening a file in another app

Every file entry, from a PUT, `file_list` or `file_info`, has a `uri`.
Pass it to `send_intent` as `data`:

```
send_intent
{
  "action": "android.intent.action.VIEW",
  "data": "<uri>",
  "package": "<package>"
}
```

`send_intent` attaches read access to the file for the receiving app
whenever the URI is one from these folders; nothing needs to be added to
the call. Without `package`, Android picks among every app that handles
the file, which is a chooser on screen when there is no default;
`intent_resolve` with the same arguments lists the candidates.

`mime_type` is optional. Left off, Android takes the type from the file
name's extension, and `application/octet-stream` when it does not know
the extension. Set `mime_type` to have the file handled as a different
type.

The rest is `send_intent` as for any launch, the locked phone included:
see [app-launch.md](app-launch.md).
