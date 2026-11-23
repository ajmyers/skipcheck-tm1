"""Rules the library's code keeps, checked over its source: a port of the Java client's
ArchitectureTest, which checks the same over the compiled classes. Each one is a mistake that's
easy to make, passes most tests, and goes wrong in production; its ``because`` says why. The rules
are strict: nothing breaks them today, so any new break fails the tests.

Every Java rule has its counterpart here, under its Java name where the meaning carries over:
Reactor's blocking calls become what blocks an event loop, a bare ``subscribe()`` becomes a task or
thread nobody awaits, WebClient's URI templates become URLs written into strings, and SLF4J
becomes the standard logging module.
"""

from __future__ import annotations

import ast
from collections.abc import Iterator
from pathlib import Path

LIBRARY = Path(__file__).resolve().parents[1] / "src" / "skipcheck_tm1"
AIO = LIBRARY / "aio"
SERVICES = AIO / "services"


def modules(root: Path = LIBRARY) -> list[tuple[str, ast.Module]]:
    """Every module under ``root``, the generated ones included, with its path from the library."""
    found = [(path.relative_to(LIBRARY).as_posix(), ast.parse(path.read_text(encoding="utf-8"))) for path in sorted(root.rglob("*.py"))]
    assert found, f"no modules in {root}"
    return found


def dotted(node: ast.AST) -> str:
    """``a.b.c`` for a name or an attribute of one; empty for anything else."""
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        owner = dotted(node.value)
        return f"{owner}.{node.attr}" if owner else ""
    return ""


def calls(tree: ast.AST) -> Iterator[tuple[ast.Call, str]]:
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            yield node, dotted(node.func)


def imports(tree: ast.Module, module: str) -> set[str]:
    """What a module imports, as absolute module names (``module`` is its own, to resolve ``.x``)."""
    out = set()
    package = module.split(".")[:-1]
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            out.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            base = package[: len(package) - node.level + 1] if node.level else []
            name = ".".join([*base, *([node.module] if node.module else [])])
            out.add(name)
            out.update(f"{name}.{alias.name}" for alias in node.names)
    return out


def docstrings(tree: ast.AST) -> set[int]:
    """The ids of the string nodes that are docstrings: documentation, not code."""
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                out.add(id(first.value))
        # A string on its own after an assignment documents the attribute
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            out.add(id(node.value))
    return out


def services() -> list[tuple[str, ast.ClassDef]]:
    """The services: the classes named ``*Service`` in ``skipcheck_tm1.aio.services``. The sync
    flavor's are generated from them."""
    found = [
        (name, node)
        for name, tree in modules(SERVICES)
        for node in tree.body
        if isinstance(node, ast.ClassDef) and node.name.endswith("Service") and node.name != "Service"
    ]
    assert len(found) > 25, "the services weren't found"
    return found


def public_methods(cls: ast.ClassDef) -> list[ast.FunctionDef | ast.AsyncFunctionDef]:
    return [node for node in cls.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and not node.name.startswith("_")]


def test_never_blocks_the_event_loop() -> None:
    because = (
        "the asyncio flavor never blocks (Java's neverBlocksOnAReactiveChain): blocking the event loop stalls every request on it, "
        "and callers who want to block use the sync flavor. Waits go through _compat's runtime, which each flavor does its own way"
    )
    found = []
    for name, tree in modules(AIO):
        for call, target in calls(tree):
            if target in ("time.sleep", "asyncio.run", "httpx.Client") or target.endswith((".run_until_complete", ".run_in_executor")):
                found.append(f"{name}:{call.lineno} calls {target}")
    assert not found, "\n".join([*found, because])


def test_never_starts_work_nobody_awaits() -> None:
    because = (
        "a task or a thread the library starts runs outside the caller's call: its errors go nowhere and the caller can't cancel "
        "it. Return the coroutine instead (Java's neverStartsWorkNobodySubscribedTo)"
    )
    found = []
    for name, tree in modules():
        for call, target in calls(tree):
            if target in ("asyncio.ensure_future", "asyncio.run_coroutine_threadsafe", "threading.Thread") or target.endswith((".create_task", ".submit")):
                found.append(f"{name}:{call.lineno} calls {target}")
    assert not found, "\n".join([*found, because])


def test_builds_request_urls_rather_than_writing_them() -> None:
    because = (
        "TM1 object names contain quotes, slashes and braces (control objects such as }Clients), which a path written into a "
        "string doesn't encode; requests use a path and query already encoded by ODataPath and ODataQuery "
        "(Java's buildsRequestUrisRatherThanUsingUriTemplates)"
    )
    found = []
    for name, tree in modules(SERVICES):
        documentation = docstrings(tree)
        for node in ast.walk(tree):
            # A key or a query option in a string that values are formatted into
            if isinstance(node, ast.JoinedStr):
                text = "".join(part.value for part in node.values if isinstance(part, ast.Constant) and isinstance(part.value, str))
                formatted = any(isinstance(part, ast.FormattedValue) for part in node.values)
                if formatted and ("('" in text or "?$" in text or "&$" in text):
                    found.append(f"{name}:{node.lineno} formats a value into the URL {text!r}")
            elif isinstance(node, ast.Call) and dotted(node.func).endswith((".format", "ODataPath.link")) and id(node) not in documentation:
                owner = node.func.value if isinstance(node.func, ast.Attribute) else None
                if isinstance(owner, ast.Constant) and isinstance(owner.value, str) and "('" in owner.value:
                    found.append(f"{name}:{node.lineno} formats a value into the URL {owner.value!r}")
    assert not found, "\n".join([*found, because])


def test_logs_through_logging_only() -> None:
    because = (
        "logging goes through the standard logging module, on the logger named skipcheck_tm1, so applications control where it "
        "goes and the library never writes to the console (Java's logsThroughSlf4jOnly)"
    )
    found = []
    for name, tree in modules():
        for call, target in calls(tree):
            if target == "print" or target.startswith(("sys.stdout", "sys.stderr", "traceback.print", "warnings.warn", "logging.basicConfig")):
                found.append(f"{name}:{call.lineno} calls {target}")
            if target == "logging.getLogger":
                argument = call.args[0] if call.args else None
                if not (isinstance(argument, ast.Constant) and argument.value == "skipcheck_tm1"):
                    found.append(f"{name}:{call.lineno} logs to another logger than skipcheck_tm1")
        for imported in imports(tree, "skipcheck_tm1." + name.removesuffix(".py").replace("/", ".")):
            if imported.split(".")[0] in ("loguru", "structlog"):
                found.append(f"{name} imports {imported}")
    assert not found, "\n".join([*found, because])


def test_keeps_the_odata_layer_free_of_tm1() -> None:
    because = (
        "the odata layer is TM1-agnostic: OData correctness lives in one place and doesn't depend on sessions, requests or TM1 "
        "versions (Java's keepsTheODataLayerFreeOfTm1)"
    )
    tree = ast.parse((LIBRARY / "odata.py").read_text(encoding="utf-8"))
    found = sorted(imported for imported in imports(tree, "skipcheck_tm1.odata") if imported.split(".")[0] in ("skipcheck_tm1", "httpx", "pydantic"))
    assert not found, "\n".join([f"odata.py imports {imported}" for imported in found] + [because])


def test_services_are_stateless_classes_built_from_a_session() -> None:
    because = (
        "services are free to create and safe to share: everything they need is the session "
        "(Java's servicesAreStatelessFinalClassesBuiltFromASession; Python has no final classes, so no service extends another)"
    )
    found = []
    names = {cls.name for _, cls in services()}
    for name, cls in services():
        bases = [dotted(base) for base in cls.bases]
        if bases != ["Service"]:
            found.append(f"{name}: {cls.name} extends {bases}, not Service alone")
        if names & set(bases):
            found.append(f"{name}: {cls.name} extends another service")
        for node in ast.walk(cls):
            if isinstance(node, ast.FunctionDef) and node.name == "__init__":
                found.append(f"{name}: {cls.name} has a constructor of its own; Service's takes the session")
            targets = node.targets if isinstance(node, ast.Assign) else [node.target] if isinstance(node, (ast.AnnAssign, ast.AugAssign)) else []
            for target in targets:
                if isinstance(target, ast.Attribute) and dotted(target.value) == "self":
                    found.append(f"{name}:{node.lineno} {cls.name} keeps state in self.{target.attr}")
    base = ast.parse((SERVICES / "_base.py").read_text(encoding="utf-8"))
    (service,) = [node for node in base.body if isinstance(node, ast.ClassDef) and node.name == "Service"]
    (init,) = [node for node in service.body if isinstance(node, ast.FunctionDef) and node.name == "__init__"]
    if [argument.arg for argument in init.args.args] != ["self", "session"]:
        found.append("_base.py: Service's constructor takes more than the session")
    kept = {target.attr for node in ast.walk(service) if isinstance(node, ast.Assign) for target in node.targets if isinstance(target, ast.Attribute)}
    if kept != {"_session"}:
        found.append(f"_base.py: Service keeps {sorted(kept)}, not the session alone")
    assert not found, "\n".join([*found, because])


def test_service_methods_are_async_and_declare_their_endpoints() -> None:
    because = (
        "each service method is one REST call that never blocks, and the coverage gate counts what's implemented from "
        "@endpoint (Java's serviceMethodsAreReactiveAndDeclareTheirEndpoints)"
    )
    found = []
    for name, cls in services():
        for method in public_methods(cls):
            returns = ast.unparse(method.returns) if method.returns is not None else ""
            if not isinstance(method, ast.AsyncFunctionDef) and not returns.startswith(("AsyncIterator[", "AsyncGenerator[")):
                found.append(f"{name}:{method.lineno} {cls.name}.{method.name} is neither async nor returns an async iterator")
            decorators = {dotted(d.func if isinstance(d, ast.Call) else d) for d in method.decorator_list}
            if "endpoint" not in decorators:
                found.append(f"{name}:{method.lineno} {cls.name}.{method.name} doesn't declare its endpoints with @endpoint")
    assert not found, "\n".join([*found, because])
