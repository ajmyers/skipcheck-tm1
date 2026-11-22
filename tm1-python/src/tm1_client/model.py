"""The TM1 REST API's model: an enum or a pydantic model per CSDL type, generated from the
API spec (see :mod:`tm1_client._generated.model`)."""

from ._generated.model import *  # noqa: F403
from ._generated.model import __all__  # noqa: F401
from ._generated.operations import Tm1Operations  # noqa: E402,F401
from ._generated.patches import *  # noqa: E402,F401,F403
