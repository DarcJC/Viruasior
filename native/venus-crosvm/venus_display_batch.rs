#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    fn run(begin_error: Option<(u32,i32)>,end_error: Option<(u32,i32)>) -> (i32,Vec<(char,u32)>) {
        let trace=RefCell::new(Vec::new());
        let ret=wait_all(&[3,7,9],|id| {
            trace.borrow_mut().push(('b',id));
            begin_error.filter(|e|e.0==id).map_or(0,|e|e.1)
        },|id| {
            trace.borrow_mut().push(('e',id));
            end_error.filter(|e|e.0==id).map_or(0,|e|e.1)
        });
        (ret,trace.into_inner())
    }
    #[test]
    fn all_requests_precede_waits() {
        assert_eq!(run(None,None),(0,vec![('b',3),('b',7),('b',9),('e',3),('e',7),('e',9)]));
    }
    #[test]
    fn failed_send_does_not_await_a_missing_reply_and_drains_other_replies() {
        assert_eq!(run(Some((7,-5)),None),(-5,vec![('b',3),('b',7),('b',9),('e',3),('e',9)]));
    }
    #[test]
    fn failed_reply_still_drains_later_replies() {
        let (ret,trace)=run(None,Some((3,-11)));
        assert_eq!(ret,-11); assert_eq!(&trace[3..],&[('e',3),('e',7),('e',9)]);
    }
    #[test]
    fn fatal_error_is_not_hidden_by_an_earlier_deferred_result() {
        assert_eq!(run(Some((3,-11)),Some((7,-5))).0,-5);
    }
    #[test]
    fn empty_set_needs_no_wait() {
        assert_eq!(wait_all(&[],|_|panic!(),|_|panic!()),0);
    }
}
/// Keep every producer completion edge, but overlap independent renderer round trips.
/// Always drain successful requests, including after an error, to preserve socket framing.
pub(crate) fn wait_all(ids: &[u32],mut begin: impl FnMut(u32)->i32,mut end: impl FnMut(u32)->i32)->i32 {
    let mut result=0;
    let mut remember=|error| {
        if error!=0 && (result==0 || result == -11 && error != -11) {result=error;}
    };
    let mut pending=Vec::with_capacity(ids.len());
    for &id in ids {
        let ret=begin(id);
        if ret==0 {pending.push(id);} else {remember(ret);}
    }
    for id in pending {remember(end(id));}
    result
}
