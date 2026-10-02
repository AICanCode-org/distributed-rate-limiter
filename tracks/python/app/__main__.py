"""`python -m app` starts the server on $PORT (default 8080)."""

import uvicorn

from .config import Settings

if __name__ == "__main__":
    uvicorn.run("app.asgi:app", host="0.0.0.0", port=Settings.from_env().port)
