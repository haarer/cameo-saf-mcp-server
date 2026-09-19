import os
import httpx
import pytest

SERVER_URL = os.environ.get("SERVER_URL", "http://localhost:18750")
MCP_AUTH_TOKEN = os.environ.get("MCP_AUTH_TOKEN")


@pytest.fixture(scope="session")
def client():
    headers = {}
    if MCP_AUTH_TOKEN:
        headers["Authorization"] = f"Bearer {MCP_AUTH_TOKEN}"
    with httpx.Client(base_url=SERVER_URL, timeout=30, headers=headers) as c:
        yield c