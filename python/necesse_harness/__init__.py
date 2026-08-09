"""Drive a real Necesse dedicated server from Python.

Needs the Necesse Headless Harness mod installed in the game's mods folder, which needs a licensed
install of the game. There is no way to run any of this on a hosted CI runner.
"""

from .client import Harness
from .process import HarnessError, HarnessServer, ServerConfig, ServerDied
from .rpc import PROTOCOL_VERSION, Check, Reply

__all__ = [
    "Harness", "HarnessError", "HarnessServer", "ServerConfig", "ServerDied",
    "PROTOCOL_VERSION", "Check", "Reply",
]

__version__ = "0.1.0"
