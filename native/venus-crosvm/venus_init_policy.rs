// Extracted backend-result policy, also compiled standalone for regression tests.
pub fn initialization<T, E>(venus: bool, result: Result<T, E>) -> Result<Option<T>, E> {
    match result {
        Ok(renderer) => Ok(Some(renderer)),
        Err(error) if venus => Err(error),
        Err(_) => Ok(None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn explicit_venus_must_preserve_the_initialization_error() {
        assert_eq!(initialization::<(), _>(true, Err(13)), Err(13));
    }
    #[test]
    fn unrelated_legacy_backend_retains_its_fallback() {
        assert_eq!(initialization::<(), _>(false, Err(13)), Ok(None));
    }
    #[test]
    fn successful_renderer_is_preserved() {
        assert_eq!(initialization::<_, ()>(true, Ok(42)), Ok(Some(42)));
    }
}
