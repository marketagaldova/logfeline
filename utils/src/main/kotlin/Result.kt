@file:OptIn(ExperimentalContracts::class)
@file:Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")

package logfeline.utils.result

import kotlin.contracts.*


@JvmInline value class Result<out Value, out Error>
    @PublishedApi internal constructor(@PublishedApi internal val value: Any?)
{
    @PublishedApi internal data class Failure(val error: Any?)
    
    val isSuccess get() = value !is Failure
    val isFailure get() = value is Failure
    
    companion object {
        inline fun success(): Result<Unit, Nothing> = Result(Unit)
        inline fun <Value> success(value: Value): Result<Value, Nothing> = Result(value)
        inline fun <Error> failure(error: Error): Result<Nothing, Error> = Result(Failure(error))
    }
}


inline fun <Value, Error> Result<Value, Error>.onSuccess(block: (value: Value) -> Unit): Result<Value, Error> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (value !is Result.Failure) block(value as Value)
    return this
}
inline fun <Value, Error> Result<Value, Error>.onFailure(block: (error: Error) -> Unit): Result<Value, Error> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    if (value is Result.Failure) block(value.error as Error)
    return this
}
inline fun <Value, Error> Result<Value, Error>.forwardFailure(block: (Result<Nothing, Error>) -> Nothing): Value {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (value is Result.Failure) block(this as Result<Nothing, Error>) else value as Value
}

inline fun <Value, Error> Result<Value, Error>.getOrNull() = if (value is Result.Failure) null else value as Value
inline fun <Value, Error> Result<Value, Error>.getOrDefault(default: Value) = if (value is Result.Failure) default else value as Value
inline fun <Value, Error> Result<Value, Error>.getOrElse(block: (error: Error) -> Value): Value {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (value is Result.Failure) block(value.error as Error) else value as Value
}

inline fun <OldValue, NewValue, Error> Result<OldValue, Error>.mapValue(block: (OldValue) -> NewValue): Result<NewValue, Error> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (value is Result.Failure) this as Result<NewValue, Error> else Result(block(value as OldValue))
}
inline fun <Value, OldError, NewError> Result<Value, OldError>.mapError(block: (OldError) -> NewError): Result<Value, NewError> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (value is Result.Failure) Result.failure(block(value.error as OldError)) else this as Result<Value, NewError>
}


inline fun <Value> attempt(block: () -> Value): Result<Value, Exception> {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return try { Result.success(block()) }
    catch (e: Exception) { Result.failure(e) }
}

inline infix fun <Value, Exception : kotlin.Exception, CaughtException : Exception,  Error> Result<Value, Exception>.catch(
    block: (exception: CaughtException) -> Error,
): Result<Value, Error> {
    contract {
        callsInPlace(block, InvocationKind.AT_MOST_ONCE)
    }
    return if (value is Result.Failure) Result.failure(block(value.error as? CaughtException ?: throw value.error as Exception))
    else Result(value)
}


inline fun <Value, Error> result(block: ResultScope<Value, Error>.() -> Value): Result<Value, Error> {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    return try { Result.success((ResultScope as ResultScope<Value, Error>).block()) }
    catch (e: ResultScope.Error) { Result.failure(e.error as Error) }
}

abstract class ResultScope<Value, Error> {
    inline fun <Value> Result<Value, Error>.getOrFail(): Value = getOrElse { e -> throw Error(e) }
    inline fun <Value, InnerError> Result<Value, InnerError>.getOrFail(block: (InnerError) -> Error): Value {
        contract {
            callsInPlace(block, InvocationKind.AT_MOST_ONCE)
        }
        return getOrElse { e -> throw Error(block(e)) }
    }

    companion object Instance : ResultScope<Nothing, Nothing>()
    class Error(val error: Any?) : Exception()
}

inline fun <Error> ResultScope<*, Error>.fail(error: Error): Nothing = throw ResultScope.Error(error)
