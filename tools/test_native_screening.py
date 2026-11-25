"""Screening policy is deliberately distinct from native acceptance."""
import unittest
import volumetric_weight_ci as ci


class NativeScreeningTests(unittest.TestCase):
    def pair(self, one=3.5, nine=0.0, control=10.0, trial=9.0):
        def stage(world):
            return dict(gpuCaptureMs=.2, gpuWorldMs=world,
                        gpuRestoreMs=.1, gpuFinalizeMs=1.0)
        return dict(fpsChangePct={"one-mirror": one, "nine-coplanar-mirrors": nine},
                    stagesMs={"control": stage(control), "trial": stage(trial)})

    def test_promising_is_not_accepted(self):
        pair = self.pair()
        result = ci.evaluate_screening(pair)
        self.assertEqual("promising", result["decision"])
        self.assertFalse(result["productionAccepted"])
        with self.assertRaisesRegex(AssertionError, "Three stable"):
            ci.evaluate_gpu_acceptance([pair])

    def test_reject_correlated_loss(self):
        self.assertEqual("reject", ci.evaluate_screening(
            self.pair(one=-5.826, trial=10.5))["decision"])

    def test_inconclusive_conflicting_signals(self):
        self.assertEqual("inconclusive", ci.evaluate_screening(
            self.pair(one=-3, trial=9.8))["decision"])
        self.assertEqual("inconclusive", ci.evaluate_screening(
            self.pair(one=.7, trial=9.85))["decision"])

    def test_reject_nonfinite(self):
        with self.assertRaises(AssertionError):
            ci.evaluate_screening(self.pair(one=float("nan")))
