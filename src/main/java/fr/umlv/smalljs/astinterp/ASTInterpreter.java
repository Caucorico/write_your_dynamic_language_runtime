package fr.umlv.smalljs.astinterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.stream.Collectors;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.ast.Visitor;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public class ASTInterpreter {
    private static <T> T as(Object value, Class<T> type, Expr failedExpr) {
        try {
            return type.cast(value);
        } catch(@SuppressWarnings("unused") ClassCastException e) {
            throw new Failure("at line " + failedExpr.lineNumber() + ", type error " + value + " is not a " + type.getSimpleName());
        }
    }

    static Object visit(Expr expr, JSObject env) {
        return VISITOR.visit(expr, env);
    }

    private static final Visitor<JSObject, Object> VISITOR =
            new Visitor<JSObject, Object>()
                    .when(Block.class, (block, env) -> {
                        for  ( var instr : block.instrs() ) {
                            visit(instr, env);
                        }
                        return UNDEFINED;
                    })
                    .when(Literal.class, (literal, env) -> literal.value())
                    
                    .when(FunCall.class, (funCall, env) -> {
                        var value = visit(funCall.qualifier(), env);
                        var function = as(value, JSObject.class, funCall );
                        var arguments = funCall.args().stream().map(arg -> visit(arg, env)).toArray();
                        return function.invoke(UNDEFINED, arguments);
                    })
                    .when(LocalVarAccess.class, (localVarAccess, env) -> env.lookup(localVarAccess.name()))

                    .when(LocalVarAssignment.class, (localVarAssignment, env) -> {

                        /*if ( localVarAssignment.declaration() && env.lookup(localVarAssignment.name()) != UNDEFINED) {
                            throw new Failure("variable " + localVarAssignment.name() + " already defined");
                        }*/

                        if ( !localVarAssignment.declaration() && env.lookup(localVarAssignment.name()) == UNDEFINED) {
                            throw new Failure("no variable " + localVarAssignment.name() + " defined");
                        }

                        var visit = visit(localVarAssignment.expr(), env);
                        env.register(localVarAssignment.name(), visit);
                        return UNDEFINED;
                    })
                    .when(Fun.class, (fun, env) -> {
                        /*if (fun.name().isPresent()) {
                            if ( env.lookup(fun.name().get()) != UNDEFINED ) {
                                throw new Failure("function " + fun.name() + " already defined");
                            }
                        }*/
                        var functionName = fun.name().orElse("lambda");

                        JSObject.Invoker invoker = (jsObject, receiver, args) -> {
                            if ( fun.parameters().size() != args.length ) {
                                throw new Failure("Wrong number of arguments at " + fun.lineNumber());
                            }

                            var newEnv = JSObject.newEnv(env);
                            newEnv.register("this", receiver);
                            for ( var i = 0 ; i < args.length ; i++ ) {
                                newEnv.register(fun.parameters().get(i), args[i]);
                            }
                            try {
                                return visit(fun.body(), newEnv);
                            } catch (ReturnError error) {
                                return error.getValue();
                            }
                        };
                        var function = JSObject.newFunction(functionName, invoker);
                        fun.name().ifPresent(name -> env.register(name, function));
                        return function;
                    })
                    .when(Return.class, (_return, env) -> {
                        throw new ReturnError(visit(_return.expr(), env));
                    })
                    .when(If.class, (_if, env) -> {
                        var condition = visit(_if.condition(), env);
                        if ( condition.equals(0) ) {
                            return visit(_if.falseBlock(), env);
                        } else {
                            return visit(_if.trueBlock(), env);
                        }
                    })
                    .when(New.class, (_new, env) -> {
                        var object = JSObject.newObject(null);
                        _new.initMap().forEach((property, init) -> {
                            var value = visit(init, env);
                            object.register(property, value);
                        });

                        return object;
                    })
                    .when(FieldAccess.class, (fieldAccess, env) -> {
                        if ( env.lookup(fieldAccess.name()) == UNDEFINED  ) {
                            throw new Failure("Object " + fieldAccess.name() + " does not exist" );
                        }
                        
                        throw new UnsupportedOperationException("TODO FieldAccess");
                    })
                    .when(FieldAssignment.class, (fieldAssignment, env) -> {
                        throw new UnsupportedOperationException("TODO FieldAssignment");
                    })
                    .when(MethodCall.class, (methodCall, env) -> {
                        throw new UnsupportedOperationException("TODO MethodCall");
                    })
            ;

    @SuppressWarnings("unchecked")
    public static void interpret(Script script, PrintStream outStream) {
        JSObject globalEnv = JSObject.newEnv(null);
        Block body = script.body();
        globalEnv.register("global", globalEnv);
        globalEnv.register("print", JSObject.newFunction("print", (self, receiver, args) -> {
            System.err.println("print called with " + Arrays.toString(args));
            outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
            return UNDEFINED;
        }));
        globalEnv.register("+", JSObject.newFunction("+", (self, receiver, args) -> (Integer) args[0] + (Integer) args[1]));
        globalEnv.register("-", JSObject.newFunction("-", (self, receiver, args) -> (Integer) args[0] - (Integer) args[1]));
        globalEnv.register("/", JSObject.newFunction("/", (self, receiver, args) -> (Integer) args[0] / (Integer) args[1]));
        globalEnv.register("*", JSObject.newFunction("*", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));
        globalEnv.register("%", JSObject.newFunction("%", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));

        globalEnv.register("==", JSObject.newFunction("==", (self, receiver, args) -> args[0].equals(args[1]) ? 1 : 0));
        globalEnv.register("!=", JSObject.newFunction("!=", (self, receiver, args) -> !args[0].equals(args[1]) ? 1 : 0));
        globalEnv.register("<", JSObject.newFunction("<",   (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
        globalEnv.register("<=", JSObject.newFunction("<=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
        globalEnv.register(">", JSObject.newFunction(">",   (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
        globalEnv.register(">=", JSObject.newFunction(">=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
        visit(body, globalEnv);
    }
}

